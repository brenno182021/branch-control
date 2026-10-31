package br.com.el.branchcontrol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CommonHistoryEquivalenceTest {
    @Test void fingerprintPreflightFailurePreservesStatusWithoutSensitiveMessageOrCause() {
        var guarded=new GitRunner(30,()->{throw new AppException(503,"Authorization: Bearer REVIEW_DUMMY");});
        var equivalence=new GitPatchEquivalence(guarded);
        var candidate=new Models.Commit("a".repeat(40),"2026-10-22T12:00:00Z","pending");
        var error=assertThrows(AppException.class,()->equivalence.pending(repo,"b".repeat(40),List.of(candidate)));
        assertEquals(503,error.status());
        assertEquals("Não foi possível verificar a configuração da comparação Git.",error.getMessage());
        assertNull(error.getCause());
    }

    @Test void malformedSensitiveConfigAtFinalFingerprintIsPrivateAndPublishesNoPartialCache() throws Exception {
        init();
        String hash=commit("pending","pending\n","2026-10-22T12:00:00Z");
        String target=command("rev-parse","target");
        var candidates=List.of(new Models.Commit(hash,"2026-10-22T12:00:00Z","pending"));
        Path config=repo.resolve(".git/config");
        byte[] original=Files.readAllBytes(config);
        var calls=new ArrayList<String>();
        GitRunner corrupting=new GitRunner(30) {
            boolean corrupt=true;
            @Override public Result require(Path path,String... args) {
                // Explicit file listing exercises Git versions that stream parsed config before a syntax failure.
                if(Arrays.equals(args,new String[]{"config","--show-origin","--list"}))
                    return super.require(path,"config","--file",config.toString(),"--show-origin","--list");
                return super.require(path,args);
            }
            @Override public Result requirePipeline(Path path,List<String> producer,List<String> consumer) {
                calls.add(producer.get(0));
                Result result=super.requirePipeline(path,producer,consumer);
                if(corrupt && producer.get(0).equals("show")) {
                    corrupt=false;
                    try {
                        Files.writeString(config,"\n[http]\n\textraHeader = Authorization: Bearer REVIEW_DUMMY\n[broken\n",StandardOpenOption.APPEND);
                    } catch(java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
                }
                return result;
            }
        };
        var equivalence=new GitPatchEquivalence(corrupting);
        AppException error;
        try { error=assertThrows(AppException.class,()->equivalence.pending(repo,target,candidates)); }
        finally { Files.write(config,original); }
        assertEquals(422,error.status());
        assertEquals("Não foi possível verificar a configuração da comparação Git.",error.getMessage());
        assertFalse(error.getMessage().contains("REVIEW_DUMMY"));
        assertFalse(error.getMessage().contains("Authorization"));
        assertNull(error.getCause());
        assertEquals(candidates,equivalence.pending(repo,target,candidates));
        assertEquals(List.of("log","show","log","show"),calls);
        assertEquals(candidates,equivalence.pending(repo,target,candidates));
        assertEquals(4,calls.size());
    }

    @Test void warmComparisonDoesNotRepeatNativeDiffs() throws Exception {
        init();
        commit("pending", "pending\n", "2026-10-22T12:00:00Z");
        var calls = new ArrayList<List<String>>();
        GitRunner counting = new GitRunner(30) {
            @Override public Result requirePipeline(Path path, List<String> producer, List<String> consumer) {
                calls.add(producer); return super.requirePipeline(path, producer, consumer);
            }
        };
        var service = new GitService(counting, new AppProperties(repo.toString(), "UTC", 30));
        var first = service.prepare(repo.toString(), "source", "target", LocalDate.parse("2026-10-24"));
        int cold = calls.size();
        assertEquals(first.commits(), service.prepare(repo.toString(), "source", "target", LocalDate.parse("2026-10-24")).commits());
        assertEquals(cold, calls.size());
    }

    @Test void extendsOnlyVerifiedAncestorAndInvalidatesConfigurationAndAttributes() throws Exception {
        init();
        String copied=commit("copied", "copied\n", "2026-10-21T12:00:00Z");
        String pending=commit("pending", "pending\n", "2026-10-22T12:00:00Z");
        var calls=new ArrayList<List<String>>();
        GitRunner counting=new GitRunner(30) {
            @Override public Result requirePipeline(Path path,List<String> producer,List<String> consumer) {
                calls.add(producer); return super.requirePipeline(path,producer,consumer);
            }
        };
        var service=new GitService(counting,new AppProperties(repo.toString(),"UTC",30));
        String base=command("rev-parse","target");
        var before=service.prepare(repo.toString(),"source","target",LocalDate.parse("2026-10-24"));
        command("switch","-q","target"); command("cherry-pick",copied);
        String applied=command("rev-parse","HEAD");
        calls.clear();
        assertEquals(List.of(pending),service.prepare(repo.toString(),"source","target",LocalDate.parse("2026-10-24")).commits().stream().map(Models.Commit::hash).toList());
        assertEquals(1,calls.size());
        assertTrue(calls.get(0).contains(base+".."+applied));
        command("config","diff.algorithm","histogram"); calls.clear();
        assertEquals(1,service.prepare(repo.toString(),"source","target",LocalDate.parse("2026-10-24")).commits().size());
        assertEquals(2,calls.size());
        Files.writeString(repo.resolve(".gitattributes"),"pending -diff\n"); calls.clear();
        assertEquals(1,service.prepare(repo.toString(),"source","target",LocalDate.parse("2026-10-24")).commits().size());
        assertEquals(2,calls.size());
        command("reset","--hard",base); calls.clear();
        assertEquals(before.commits(),service.prepare(repo.toString(),"source","target",LocalDate.parse("2026-10-24")).commits());
        assertTrue(calls.get(0).contains(base));
        assertFalse(calls.get(0).stream().anyMatch(arg->arg.contains("..")));
    }

    @Test void failedSourceScanDoesNotPublishPartialTargetOrSourceCache() throws Exception {
        init(); commit("pending","pending\n","2026-10-22T12:00:00Z");
        var calls=new ArrayList<String>();
        GitRunner failing=new GitRunner(30) {
            boolean fail=true;
            @Override public Result requirePipeline(Path path,List<String> producer,List<String> consumer) {
                calls.add(producer.get(0));
                if(fail && producer.get(0).equals("show")) { fail=false; throw new AppException(422,"fixture failure"); }
                return super.requirePipeline(path,producer,consumer);
            }
        };
        var service=new GitService(failing,new AppProperties(repo.toString(),"UTC",30));
        assertThrows(AppException.class,()->service.prepare(repo.toString(),"source","target",LocalDate.parse("2026-10-24")));
        assertEquals(1,service.prepare(repo.toString(),"source","target",LocalDate.parse("2026-10-24")).commits().size());
        assertEquals(List.of("log","show","log","show"),calls);
    }

    @Test void resolvesRelativeAttributesFromRepositoryAndSupportsExplicitlyDisabledGlobalAttributes() throws Exception {
        init(); commit("pending","pending\n","2026-10-22T12:00:00Z");
        var calls=new ArrayList<String>();
        GitRunner counting=new GitRunner(30) {
            @Override public Result requirePipeline(Path path,List<String> producer,List<String> consumer) {
                calls.add(producer.get(0)); return super.requirePipeline(path,producer,consumer);
            }
        };
        var service=new GitService(counting,new AppProperties(repo.toString(),"UTC",30));
        command("config","core.attributesFile","custom.attributes");
        Files.writeString(repo.resolve("custom.attributes"),"pending diff\n");
        assertEquals(1,service.prepare(repo.toString(),"source","target",LocalDate.parse("2026-10-24")).commits().size());
        calls.clear();
        Files.writeString(repo.resolve("custom.attributes"),"pending -diff\n");
        assertEquals(1,service.prepare(repo.toString(),"source","target",LocalDate.parse("2026-10-24")).commits().size());
        assertEquals(List.of("log","show"),calls);
        command("config","core.attributesFile","");
        assertEquals(1,service.prepare(repo.toString(),"source","target",LocalDate.parse("2026-10-24")).commits().size());
    }
    @TempDir Path repo;
    final GitRunner git = new GitRunner(30);
    String command(String... args) { return git.require(repo, args).output().strip(); }
    String commit(String file, String text, String date) throws Exception {
        Files.writeString(repo.resolve(file), text);
        command("add", file);
        git.require(repo, Map.of("GIT_AUTHOR_DATE", date, "GIT_COMMITTER_DATE", date), "commit", "-qm", file);
        return command("rev-parse", "HEAD");
    }
    void init() throws Exception {
        command("init", "-q", "--initial-branch=target");
        command("config", "user.name", "Fixture"); command("config", "user.email", "fixture@example.invalid");
        command("config", "commit.gpgsign", "false"); command("config", "core.autocrlf", "false");
        command("config", "core.hooksPath", repo.resolve("no-hooks").toString());
        commit("base", "base\n", "2026-10-20T12:00:00Z");
        command("switch", "-qc", "source");
    }
    GitService service() { return new GitService(git, new AppProperties(repo.toString(), "UTC", 30)); }
    Models.Plan preview() { return service().prepare(repo.toString(), "source", "target", LocalDate.parse("2026-10-24")); }

    @Test void excludesEquivalentInCommonHistoryAndCanonicalImport() throws Exception {
        init();
        String original = commit("copied", "same patch\n", "2026-10-21T12:00:00Z");
        String pending = commit("pending", "pending\n", "2026-10-22T12:00:00Z");
        String last = commit("last", "last\n", "2026-10-24T23:59:59Z");
        commit("future", "future\n", "2026-10-25T00:00:00Z");
        command("switch", "-q", "target");
        git.require(repo, Map.of("GIT_COMMITTER_DATE", "2026-10-29T12:00:00Z"), "cherry-pick", original);
        String copy = command("rev-parse", "HEAD");
        assertNotEquals(original, copy);
        command("switch", "-q", "source");
        command("merge", "--no-ff", "-m", "merge target back", "target");
        assertEquals(0, git.run(repo, "merge-base", "--is-ancestor", copy, "source").code());
        assertEquals(1, git.run(repo, "merge-base", "--is-ancestor", original, "target").code());
        var plan = preview();
        assertEquals(List.of(pending, last), plan.commits().stream().map(Models.Commit::hash).toList());
        String text = service().exportText(plan, List.of(last, pending))
                + "2026-10-21T12:00:00Z\tcopied\t" + original + "\r\n";
        assertEquals(List.of(pending, last), service().importText(repo.toString(), "target", text).commits().stream().map(Models.Commit::hash).toList());
    }

    @Test void binaryEquivalentIsExcludedButDifferentPatchSquashAndEmptyRemain() throws Exception {
        init();
        Files.write(repo.resolve("binary"), new byte[]{0, 1, 2, 3});
        command("add", "binary");
        git.require(repo, Map.of("GIT_COMMITTER_DATE", "2026-10-21T12:00:00Z"), "commit", "-qm", "binary");
        String binary = command("rev-parse", "HEAD");
        String changed = commit("changed", "one\n", "2026-10-22T12:00:00Z");
        String one = commit("squash-one", "one\n", "2026-10-22T12:00:00Z");
        String two = commit("squash-two", "two\n", "2026-10-23T12:00:00Z");
        git.require(repo, Map.of("GIT_COMMITTER_DATE", "2026-10-23T12:00:00Z"), "commit", "--allow-empty", "-qm", "metadata");
        String empty = command("rev-parse", "HEAD");
        command("switch", "-q", "target");
        git.require(repo, Map.of("GIT_COMMITTER_DATE", "2026-10-29T12:00:00Z"), "cherry-pick", binary);
        commit("changed", "different\n", "2026-10-29T12:00:00Z");
        Files.writeString(repo.resolve("squash-one"), "one\n");
        commit("squash-two", "two\n", "2026-10-29T12:00:00Z");
        // Include both paths in a single target patch, not two equivalent patches.
        command("add", "squash-one"); command("commit", "--amend", "--no-edit");
        command("config", "diff.renames", "copies");
        command("config", "diff.algorithm", "histogram");
        command("switch", "-q", "source");
        command("merge", "-s", "ours", "--no-ff", "-m", "target history", "target");
        assertEquals(List.of(changed, one, two, empty), preview().commits().stream().map(Models.Commit::hash).toList());
    }

    @Test void whitespaceEquivalenceStillAppliesInsideCommonHistory() throws Exception {
        init();
        String original = commit("spaces", "value = 1;\n", "2026-10-21T12:00:00Z");
        command("switch", "-q", "target");
        commit("spaces", "value=1;\n", "2026-10-29T12:00:00Z");
        command("switch", "-q", "source");
        command("merge", "-s", "ours", "--no-ff", "-m", "target history", "target");
        assertEquals(1, git.run(repo, "merge-base", "--is-ancestor", original, "target").code());
        assertTrue(preview().commits().isEmpty());
    }

    @Test void scansTargetOnceAndBatchesMoreThanHundredFullSourceHashes() throws Exception {
        init();
        String tree = command("rev-parse", "HEAD^{tree}");
        String parent = command("rev-parse", "HEAD");
        var commits = new ArrayList<Models.Commit>();
        for (int i = 0; i < 101; i++) {
            parent = command("commit-tree", tree, "-p", parent, "-m", "empty " + i);
            commits.add(new Models.Commit(parent, "2026-10-21T12:00:00Z", "empty " + i));
        }
        var calls = new ArrayList<List<String>>();
        GitRunner counting = new GitRunner(30) {
            @Override public Result requirePipeline(Path path, List<String> producer, List<String> consumer) {
                calls.add(producer);
                return super.requirePipeline(path, producer, consumer);
            }
        };
        assertEquals(commits, new GitPatchEquivalence(counting).pending(repo, command("rev-parse", "target"), commits));
        assertEquals(List.of("log", "show", "show"), calls.stream().map(call -> call.get(0)).toList());
        assertEquals(100, calls.get(1).stream().filter(arg -> arg.matches("[a-f0-9]{40}")).count());
        assertEquals(1, calls.get(2).stream().filter(arg -> arg.matches("[a-f0-9]{40}")).count());
    }

    @Test void targetWithoutPatchIdsKeepsEveryCandidate() throws Exception {
        command("init", "-q", "--initial-branch=target");
        command("config", "user.name", "Fixture"); command("config", "user.email", "fixture@example.invalid");
        command("config", "commit.gpgsign", "false");
        command("commit", "--allow-empty", "-qm", "empty root");
        command("switch", "-qc", "source");
        String pending = commit("pending", "pending\n", "2026-10-22T12:00:00Z");
        assertEquals(List.of(pending), preview().commits().stream().map(Models.Commit::hash).toList());
        var service=service();
        assertEquals(1,service.prepare(repo.toString(),"source","target",LocalDate.parse("2026-10-24")).commits().size());
        assertEquals(1,service.prepare(repo.toString(),"source","target",LocalDate.parse("2026-10-24")).commits().size());
    }

    @Test void streamsDiffLargerThanJavaOutputLimitThroughNativePipeline() throws Exception {
        init();
        String large = commit("large", "a".repeat(9 * 1024 * 1024) + "\n", "2026-10-22T12:00:00Z");
        command("switch", "-q", "target");
        git.require(repo, Map.of("GIT_COMMITTER_DATE", "2026-10-29T12:00:00Z"), "cherry-pick", large);
        command("switch", "-q", "source");
        command("merge", "-s", "ours", "--no-ff", "-m", "target history", "target");
        assertTrue(preview().commits().isEmpty());
        assertEquals(422, assertThrows(AppException.class, () -> git.requirePipeline(repo,
                List.of("version"), List.of("show", "source:large"))).status());
    }
}
