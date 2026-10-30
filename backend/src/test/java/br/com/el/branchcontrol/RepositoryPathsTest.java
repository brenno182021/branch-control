package br.com.el.branchcontrol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class RepositoryPathsTest {
    @TempDir Path server;
    final GitRunner runner = new GitRunner(30);

    Path checkout() throws Exception {
        Path repo = Files.createDirectories(server.resolve("outro-local/repo com espaços & teste"));
        runner.require(repo,"init","-q","--initial-branch=main");
        return repo.toRealPath();
    }
    GitService service() {
        return new GitService(runner,new AppProperties(server.resolve("logs").toString(),"UTC",30));
    }
    @Test void resolvesCheckoutInArbitraryServerPathAndSubdirectory() throws Exception {
        Path repo=checkout();
        var git=service();
        assertEquals(repo,git.resolveRepository(repo.toString()));
        assertEquals(repo,git.resolveRepository(Files.createDirectory(repo.resolve("subpasta")).toString()));
    }
    @Test void resolvesExternalWorktreeWithGitFile() throws Exception {
        Path repo=checkout();
        runner.require(repo,"-c","user.name=Teste","-c","user.email=teste@example.invalid","-c","commit.gpgsign=false","commit","--allow-empty","-qm","chore: prepara worktree temporário");
        Path worktree=server.resolve("outra-pasta/checkout");
        runner.require(repo,"worktree","add","-qb","outra",worktree.toString());
        assertTrue(Files.isRegularFile(worktree.resolve(".git")));
        assertEquals(worktree.toRealPath(),service().resolveRepository(worktree.toString()));
    }
    @Test void rejectsMissingPathsFilesAndFoldersWithoutGit() throws Exception {
        var git=service();
        assertThrows(AppException.class,()->git.resolveRepository(server.resolve("ausente").toString()));
        assertThrows(AppException.class,()->git.resolveRepository(server.toString()));
        Path file=Files.writeString(server.resolve("arquivo.txt"),"conteúdo");
        assertEquals(400,assertThrows(AppException.class,()->git.resolveRepository(file.toString())).status());
    }
}
