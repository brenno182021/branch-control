package br.com.el.branchcontrol;

import java.util.List;

public final class Models {
    private Models() {}
    public record Actor(String userId, String username, String group) {}
    public record Branch(String name, String ref, String head, boolean remote) {}
    public record RepositoryView(String path, String currentBranch, String head, boolean dirtyTracked,
                                 List<String> changedFiles, String operation, List<Branch> branches) {}
    public record Commit(String hash, String date, String message) {}
    public record Plan(String id, String repositoryPath, String sourceBranch, String targetBranch,
                       String sourceHead, String targetHead, String cutoff, String zoneId,
                       String createdAt, List<Commit> commits, String environmentId, long environmentRevision) {
        public Plan(String id, String repositoryPath, String sourceBranch, String targetBranch, String sourceHead,
                    String targetHead, String cutoff, String zoneId, String createdAt, List<Commit> commits) {
            this(id,repositoryPath,sourceBranch,targetBranch,sourceHead,targetHead,cutoff,zoneId,createdAt,commits,"",0);
        }
        public Plan inEnvironment(String environmentId, long revision) {
            return new Plan(id,repositoryPath,sourceBranch,targetBranch,sourceHead,targetHead,cutoff,zoneId,createdAt,commits,environmentId,revision);
        }
    }
    public record CommitProgress(String hash, String message, String state, String newHash) {}
    public record RunView(String id, String state, boolean simulation, String repositoryPath,
                          String sourceBranch, String targetBranch, String startedAt, String finishedAt,
                          String logPath, List<CommitProgress> commits, List<String> lines, Actor actor, String planId,
                          String environmentId, String environmentName) {
        public RunView(String id, String state, boolean simulation, String repositoryPath, String sourceBranch,
                       String targetBranch, String startedAt, String finishedAt, String logPath, List<CommitProgress> commits,
                       List<String> lines, Actor actor, String planId) {
            this(id,state,simulation,repositoryPath,sourceBranch,targetBranch,startedAt,finishedAt,logPath,commits,lines,actor,planId,"","");
        }
    }
}
