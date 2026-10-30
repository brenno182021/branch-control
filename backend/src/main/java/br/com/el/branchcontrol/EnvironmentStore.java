package br.com.el.branchcontrol;

import org.springframework.stereotype.Service;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;
import java.util.regex.Pattern;
import static br.com.el.branchcontrol.Models.*;

@Service
@DependsOnDatabaseInitialization
public class EnvironmentStore {
    public record Environment(String id,String name,String repositoryPath,List<String> branchPatterns,
                              List<String> permissions,List<String> userIds,boolean enabled,long revision) {}
    public record Change(String name,String repositoryPath,List<String> branchPatterns,List<String> permissions,List<String> userIds,boolean enabled) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final GitService git;
    public EnvironmentStore(DataSource source, GitService git) {
        jdbc = new JdbcTemplate(source); transactions = new TransactionTemplate(new DataSourceTransactionManager(source)); this.git = git;
    }
    public boolean administrator(Actor actor) { return Groups.ADM.equals(actor.group()); }
    public List<Environment> list(Actor actor) {
        List<String> ids = administrator(actor) ? jdbc.queryForList("SELECT id::text FROM bc_environment ORDER BY lower(name)",String.class)
            : jdbc.queryForList("SELECT e.id::text FROM bc_environment e JOIN bc_environment_member m ON e.id=m.environment_id WHERE m.user_id=CAST(? AS uuid) ORDER BY lower(e.name)",String.class,actor.userId());
        return ids.stream().map(this::find).map(env -> administrator(actor) ? env : new Environment(env.id(),env.name(),env.repositoryPath(),env.branchPatterns(),env.permissions(),List.of(),env.enabled(),env.revision())).toList();
    }
    Environment find(String id) {
        try { UUID.fromString(id); } catch (RuntimeException ex) { throw new AppException(404,"Ambiente não encontrado."); }
        return jdbc.query("SELECT * FROM bc_environment WHERE id=CAST(? AS uuid)",(rs,n) -> new Environment(rs.getString("id"),rs.getString("name"),rs.getString("repository_path"),
            jdbc.queryForList("SELECT pattern FROM bc_environment_branch_pattern WHERE environment_id=CAST(? AS uuid) ORDER BY position",String.class,id),
            jdbc.queryForList("SELECT permission FROM bc_environment_permission WHERE environment_id=CAST(? AS uuid) ORDER BY permission",String.class,id),
            jdbc.queryForList("SELECT user_id::text FROM bc_environment_member WHERE environment_id=CAST(? AS uuid) ORDER BY user_id",String.class,id),rs.getBoolean("enabled"),rs.getLong("revision")),id)
            .stream().findFirst().orElseThrow(() -> new AppException(404,"Ambiente não encontrado."));
    }
    public Environment save(String id,Change change,Actor actor) {
        if (!administrator(actor)) throw new AppException(403,"Somente ADM pode cadastrar ambientes.");
        if (change == null || change.name()==null || change.name().isBlank() || change.name().strip().length()>80 || change.name().chars().anyMatch(Character::isISOControl)) throw AppException.bad("Informe um nome de ambiente com até 80 caracteres.");
        String name=change.name().strip(), repository=git.resolveRepository(change.repositoryPath()).toString();
        if (change.branchPatterns()==null || change.branchPatterns().isEmpty() || change.branchPatterns().size()>50) throw AppException.bad("Configure de 1 a 50 padrões de branches.");
        if(change.branchPatterns().stream().anyMatch(Objects::isNull)) throw AppException.bad("Padrão de branch inválido.");
        var patterns = change.branchPatterns().stream().map(String::strip).distinct().toList();
        for (String pattern:patterns) validatePattern(pattern);
        if (change.permissions()==null || change.permissions().stream().anyMatch(Objects::isNull) || !Permissions.ALL.containsAll(change.permissions())) throw AppException.bad("Permissão de ambiente inválida.");
        List<String> permissions=change.permissions().stream().distinct().sorted().toList();
        if ((permissions.contains(Permissions.APPLY) && !permissions.contains(Permissions.SIMULATE)) ||
            (permissions.contains(Permissions.SIMULATE) && !permissions.contains(Permissions.PREPARE))) throw AppException.bad("Aplicar exige Simular e Preparar; Simular exige Preparar.");
        List<String> members=change.userIds()==null ? List.of() : change.userIds().stream().distinct().toList();
        if (members.size()>1000) throw AppException.bad("Selecione até 1000 usuários por ambiente.");
        for (String member:members) { try { UUID.fromString(member); } catch (RuntimeException ex) { throw AppException.bad("Usuário inválido."); } }
        String environmentId=id==null ? UUID.randomUUID().toString() : find(id).id();
        return transactions.execute(status -> {
            jdbc.queryForObject("SELECT name FROM bc_lock WHERE name='users' FOR UPDATE",String.class);
            if (jdbc.queryForObject("SELECT count(*) FROM bc_environment WHERE lower(name)=lower(?) AND id<>CAST(? AS uuid)",Long.class,name,environmentId)>0) throw AppException.conflict("Já existe um ambiente com esse nome.");
            for(String member:members) if(jdbc.queryForObject("SELECT count(*) FROM bc_user WHERE id=CAST(? AS uuid)",Long.class,member)!=1) throw AppException.bad("Um dos usuários não existe.");
            jdbc.update("""
                INSERT INTO bc_environment(id,name,repository_path,enabled,revision)
                VALUES(CAST(? AS uuid),?,?,?,1)
                ON CONFLICT(id) DO UPDATE SET name=excluded.name,repository_path=excluded.repository_path,
                enabled=excluded.enabled,revision=bc_environment.revision+1,updated_at=current_timestamp
                """,environmentId,name,repository,change.enabled());
            jdbc.update("DELETE FROM bc_environment_branch_pattern WHERE environment_id=CAST(? AS uuid)",environmentId);
            for(int i=0;i<patterns.size();i++) jdbc.update("INSERT INTO bc_environment_branch_pattern(environment_id,position,pattern) VALUES(CAST(? AS uuid),?,?)",environmentId,i,patterns.get(i));
            jdbc.update("DELETE FROM bc_environment_permission WHERE environment_id=CAST(? AS uuid)",environmentId);
            for(String permission:permissions) jdbc.update("INSERT INTO bc_environment_permission(environment_id,permission) VALUES(CAST(? AS uuid),?)",environmentId,permission);
            jdbc.update("DELETE FROM bc_environment_member WHERE environment_id=CAST(? AS uuid)",environmentId);
            for(String member:members) jdbc.update("INSERT INTO bc_environment_member(environment_id,user_id) VALUES(CAST(? AS uuid),CAST(? AS uuid))",environmentId,member);
            return find(environmentId);
        });
    }
    public Environment require(Actor actor,String id,String permission) {
        Environment env=find(id);
        if (!administrator(actor) && (!env.userIds().contains(actor.userId()) || (permission!=null && !env.permissions().contains(permission)))) throw new AppException(403,"Você não tem essa permissão neste ambiente.");
        if (!env.enabled() && !Permissions.REPORTS.equals(permission)) throw new AppException(403,"Este ambiente está desativado.");
        return env;
    }
    public RepositoryView open(Actor actor,String id) {
        Environment env=require(actor,id,null);
        if (!administrator(actor) && env.permissions().stream().noneMatch(Permissions.GIT_OPERATIONS::contains)) throw new AppException(403,"Este ambiente permite apenas relatórios.");
        RepositoryView view=git.open(env.repositoryPath());
        if (administrator(actor)) return view;
        return new RepositoryView(view.path(),allows(env,view.currentBranch())?view.currentBranch():"",view.head(),view.dirtyTracked(),view.changedFiles(),view.operation(),view.branches().stream().filter(branch -> allows(env,branch.ref())).toList());
    }
    public void branch(Actor actor,Environment env,String requested,boolean localOnly) {
        var branches=git.open(env.repositoryPath()).branches().stream().filter(branch -> (!localOnly||!branch.remote()) && (branch.ref().equals(requested)||branch.name().equals(requested))).toList();
        if(branches.size()!=1) throw AppException.bad("Branch inexistente ou ambígua. Selecione a referência completa.");
        if(!administrator(actor)&&!allows(env,branches.get(0).ref())) throw new AppException(403,"A branch não está autorizada neste ambiente.");
    }
    public void validatePlan(Actor actor,Plan plan,String permission) {
        // Recheck the account when a queued operation actually starts.
        var roles=jdbc.queryForList("SELECT user_group FROM bc_user WHERE id::text=? AND enabled",String.class,actor.userId());
        if(roles.size()!=1 || !roles.get(0).equals(actor.group())) throw new AppException(403,"A conta foi alterada ou desativada. Entre novamente.");
        Environment env=require(actor,plan.environmentId(),permission);
        if(env.revision()!=plan.environmentRevision() || !env.repositoryPath().equals(plan.repositoryPath())) throw AppException.conflict("O ambiente mudou depois da prévia. Gere uma nova lista.");
        branch(actor,env,plan.sourceBranch(),false); branch(actor,env,plan.targetBranch(),true);
    }
    public boolean canReport(Actor actor,RunView run) {
        if(administrator(actor)) return true;
        return list(actor).stream().anyMatch(env -> env.permissions().contains(Permissions.REPORTS) && env.repositoryPath().equals(run.repositoryPath()) && allows(env,run.sourceBranch()) && allows(env,run.targetBranch()));
    }
    public boolean canRead(Actor actor,RunView run) {
        if(canReport(actor,run)) return true;
        if(!actor.userId().equals(run.actor().userId()) || run.environmentId()==null || run.environmentId().isBlank()) return false;
        return list(actor).stream().anyMatch(env -> env.id().equals(run.environmentId()) && env.enabled() && env.permissions().stream().anyMatch(Permissions.GIT_OPERATIONS::contains) && env.repositoryPath().equals(run.repositoryPath()) && allows(env,run.sourceBranch()) && allows(env,run.targetBranch()));
    }
    public String name(String id) { return id==null||id.isBlank()?"Histórico legado":find(id).name(); }
    static void validatePattern(String pattern) {
        if(pattern.isBlank()||pattern.length()>300||pattern.chars().anyMatch(c -> Character.isWhitespace(c)||Character.isISOControl(c))||pattern.startsWith("refs/")||pattern.startsWith("-")||pattern.contains("\\")) throw AppException.bad("Use nomes ou padrões de branch sem refs/: por exemplo chore/rhf/* ou draft/rhf/*.");
    }
    static boolean allows(Environment env,String ref) {
        if(ref==null||ref.isBlank()) return false;
        String name=localName(ref);
        return env.branchPatterns().stream().anyMatch(pattern -> matches(pattern,name));
    }
    static String localName(String ref) {
        if(ref.startsWith("refs/heads/")) return ref.substring("refs/heads/".length());
        if(ref.startsWith("refs/remotes/")) { String remote=ref.substring("refs/remotes/".length()); int slash=remote.indexOf('/'); return slash<0?remote:remote.substring(slash+1); }
        return ref;
    }
    static boolean matches(String glob,String value) {
        int pattern=0, character=0, star=-1, retry=0;
        while(character<value.length()) {
            if(pattern<glob.length() && (glob.charAt(pattern)=='?' || glob.charAt(pattern)==value.charAt(character))) {pattern++;character++;}
            else if(pattern<glob.length() && glob.charAt(pattern)=='*') {star=pattern++;retry=character;}
            else if(star>=0) {pattern=star+1;character=++retry;}
            else return false;
        }
        while(pattern<glob.length()&&glob.charAt(pattern)=='*') pattern++;
        return pattern==glob.length();
    }
}
