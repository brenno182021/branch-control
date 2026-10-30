package br.com.el.branchcontrol;

import java.nio.file.Path;
import java.util.*;
import br.com.el.branchcontrol.Models.Commit;

/** Per-preview stable patch IDs, including target commits in common history. */
final class GitPatchEquivalence {
    private final GitRunner runner;
    GitPatchEquivalence(GitRunner runner) { this.runner = runner; }

    List<Commit> pending(Path repo, String target, List<Commit> candidates) {
        if (candidates.isEmpty()) return List.of();
        Set<String> targetIds = new HashSet<>(patchIds(repo, "log", List.of(target)).values());
        if (targetIds.isEmpty()) return List.copyOf(candidates);
        Map<String, String> sourceIds = new HashMap<>();
        // 100 full SHA-256 hashes plus flags remain below the Windows argv limit.
        for (int start = 0; start < candidates.size(); start += 100) {
            sourceIds.putAll(patchIds(repo, "show", candidates.subList(start, Math.min(start + 100, candidates.size()))
                    .stream().map(Commit::hash).toList()));
        }
        return candidates.stream().filter(commit -> !targetIds.contains(sourceIds.get(commit.hash()))).toList();
    }

    private Map<String, String> patchIds(Path repo, String command, List<String> revisions) {
        var args = new ArrayList<>(List.of(command, "--no-merges", "--root", "--format=commit %H", "-p",
                "--no-renames", "--no-ext-diff", "--no-textconv", "--no-color", "--unified=3",
                "--full-index", "--binary", "--diff-algorithm=myers", "--no-indent-heuristic"));
        args.addAll(revisions);
        args.add("--");
        String output = runner.requirePipeline(repo, args, List.of("patch-id", "--stable")).output();
        Map<String, String> ids = new HashMap<>();
        for (String line : output.lines().toList()) {
            if (line.isBlank()) continue;
            String[] fields = line.strip().split("\\s+");
            if (fields.length != 2 || !fields[0].matches("[0-9a-f]{40}|[0-9a-f]{64}")
                    || !fields[1].matches("[0-9a-f]{40}|[0-9a-f]{64}")) {
                throw new AppException(422, "Formato inesperado na resposta de equivalência do Git.");
            }
            ids.put(fields[1], fields[0]);
        }
        return ids;
    }
}
