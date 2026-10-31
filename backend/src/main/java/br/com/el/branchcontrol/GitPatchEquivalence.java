package br.com.el.branchcontrol;

import java.nio.file.*;
import java.io.IOException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import br.com.el.branchcontrol.Models.Commit;

/** Bounded process-local immutable patch indexes; failed scans never publish partial entries. */
final class GitPatchEquivalence {
    private final GitRunner runner;
    private record Context(Path repo, String fingerprint) {}
    private record Target(String head, Set<String> ids) {}
    private final Map<Context, Target> targets = bounded(16);
    private final Map<String, String> sources = bounded(20000);
    private static <K,V> Map<K,V> bounded(int limit) {
        return new LinkedHashMap<>(16, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<K,V> entry) { return size() > limit; }
        };
    }
    GitPatchEquivalence(GitRunner runner) { this.runner = runner; }

    synchronized List<Commit> pending(Path repo, String target, List<Commit> candidates) {
        if (candidates.isEmpty()) return List.of();
        Context context = new Context(repo.toAbsolutePath().normalize(), fingerprint(repo));
        Target previous = targets.get(context);
        Set<String> targetIds;
        if (previous != null && previous.head().equals(target)) targetIds = previous.ids();
        else {
            targetIds = new HashSet<>();
            String revision = target;
            if (previous != null && runner.run(repo, "merge-base", "--is-ancestor", previous.head(), target).code() == 0) {
                targetIds.addAll(previous.ids()); revision = previous.head() + ".." + target;
            }
            targetIds.addAll(patchIds(repo, "log", List.of(revision)).values());
        }
        Map<String,String> fresh = new HashMap<>();
        Map<String,String> sourceIds = new HashMap<>();
        List<String> missing = new ArrayList<>();
        String prefix = context.repo() + "\n" + context.fingerprint() + "\n";
        if (!targetIds.isEmpty()) {
            for (Commit commit : candidates) {
                String value = sources.get(prefix + commit.hash());
                if (value == null) missing.add(commit.hash()); else sourceIds.put(commit.hash(), value);
            }
            for (int start = 0; start < missing.size(); start += 100) {
                List<String> batch = missing.subList(start, Math.min(start + 100, missing.size()));
                Map<String,String> ids = patchIds(repo, "show", batch);
                for (String hash : batch) fresh.put(hash, ids.getOrDefault(hash, ""));
            }
            sourceIds.putAll(fresh);
        }
        // Fingerprint changes during a scan invalidate the entire result, including source entries.
        if (!context.fingerprint().equals(fingerprint(repo))) throw AppException.conflict("A configuração Git mudou durante a comparação. Gere a lista novamente.");
        if (targetIds.size() <= 100000) targets.put(context, new Target(target, Set.copyOf(targetIds)));
        else targets.remove(context);
        fresh.forEach((hash,id) -> sources.put(prefix + hash,id));
        Set<String> finalTargetIds = targetIds;
        return candidates.stream().filter(commit -> !finalTargetIds.contains(sourceIds.getOrDefault(commit.hash(), ""))).toList();
    }

    private String fingerprint(Path repo) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(runner.require(repo, "config", "--show-origin", "--list").output().getBytes(StandardCharsets.UTF_8));
            String replaceBase=System.getenv().getOrDefault("GIT_REPLACE_REF_BASE","refs/replace/");
            digest.update(runner.require(repo, "for-each-ref", "--format=%(refname) %(objectname)", replaceBase).output().getBytes(StandardCharsets.UTF_8));
            digest.update(runner.require(repo, "version").output().getBytes(StandardCharsets.UTF_8));
            // No exclude rules: ignored/untracked attribute files also influence native Git diffs.
            String attributesList=runner.require(repo,"ls-files","-z","--cached","--others","--",
                    ".gitattributes",":(glob)**/.gitattributes").output();
            for(String path:new TreeSet<>(Arrays.asList(attributesList.split("\u0000"))))
                if(!path.isEmpty()) addFile(digest,repo.resolve(path));
            // Git falls back to the index when a working-tree attributes file is absent.
            digest.update(runner.require(repo,"ls-files","--stage","-z","--",
                    ".gitattributes",":(glob)**/.gitattributes").output().getBytes(StandardCharsets.UTF_8));
            for (String name : List.of("info/attributes", "info/grafts", "shallow")) {
                addFile(digest,Path.of(runner.require(repo,"rev-parse","--path-format=absolute","--git-path",name).output().strip()));
            }
            String xdg=System.getenv("XDG_CONFIG_HOME");
            String defaultAttributes=xdg==null || xdg.isBlank()?"~/.config/git/attributes":Path.of(xdg,"git","attributes").toString();
            String attributes=runner.require(repo,"config","--path","--default",defaultAttributes,"--get","core.attributesFile").output().strip();
            // Native --path expands Git's HOME; an explicit empty setting disables global attributes.
            if(!attributes.isEmpty()) addFile(digest,repo.resolve(attributes));
            Path exec = Path.of(runner.require(repo,"--exec-path").output().strip());
            if(exec.getParent()!=null && exec.getParent().getParent()!=null)
                addFile(digest,exec.getParent().getParent().resolve("etc/gitattributes"));
            if(exec.getParent()!=null && exec.getParent().getParent()!=null && exec.getParent().getParent().getParent()!=null)
                addFile(digest,exec.getParent().getParent().getParent().resolve("etc/gitattributes"));
            addFile(digest,Path.of("/etc/gitattributes"));
            for(String name:List.of("GIT_ATTR_NOSYSTEM","GIT_CONFIG_SYSTEM","GIT_CONFIG_GLOBAL","GIT_REPLACE_REF_BASE","GIT_NO_REPLACE_OBJECTS"))
                digest.update((name+"="+System.getenv(name)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (AppException ex) {
            // A failed config listing can include credential-bearing values in stdout.
            throw new AppException(ex.status(),"Não foi possível verificar a configuração da comparação Git.");
        } catch (IOException | java.security.NoSuchAlgorithmException ex) {
            throw new AppException(422,"Não foi possível verificar a configuração da comparação Git.");
        }
    }
    private static void addFile(MessageDigest digest,Path path) throws IOException {
        digest.update((path.toString()+"\u0000").getBytes(StandardCharsets.UTF_8));
        if(!Files.exists(path)) { digest.update((byte)0); return; }
        digest.update((byte)1);
        digest.update(java.nio.ByteBuffer.allocate(Long.BYTES).putLong(Files.size(path)).array());
        try(var input=Files.newInputStream(path)) {
            byte[] buffer=new byte[8192];
            int count;
            while((count=input.read(buffer))!=-1) digest.update(buffer,0,count);
        }
    }

    private Map<String, String> patchIds(Path repo, String command, List<String> revisions) {
        var args = new ArrayList<>(List.of(command, "--no-merges", "--root", "--format=commit %H", "-p",
                "--no-renames", "--no-ext-diff", "--no-textconv", "--no-color", "--unified=3",
                "--full-index", "--binary", "--diff-algorithm=myers", "--no-indent-heuristic"));
        args.addAll(revisions); args.add("--");
        String output = runner.requirePipeline(repo, args, List.of("patch-id", "--stable")).output();
        Map<String, String> ids = new HashMap<>();
        for (String line : output.lines().toList()) {
            if (line.isBlank()) continue;
            String[] fields = line.strip().split("\\s+");
            if (fields.length != 2 || !fields[0].matches("[0-9a-f]{40}|[0-9a-f]{64}") || !fields[1].matches("[0-9a-f]{40}|[0-9a-f]{64}"))
                throw new AppException(422, "Formato inesperado na resposta de equivalência do Git.");
            ids.put(fields[1], fields[0]);
        }
        return ids;
    }
}
