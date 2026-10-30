package br.com.el.branchcontrol;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import javax.sql.DataSource;
import java.util.*;
import java.util.function.Supplier;
import static br.com.el.branchcontrol.Models.Actor;

@Service
public final class AccessRequestsStore {
    public record View(String id,String username,String name,String status,String createdAt,String reviewedAt,
                       String reviewerUsername,String environmentId,String environmentName,String rejectionReason) {}
    public record Submitted(String id,String status,String message) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final UsersStore users;
    private final PasswordEncoder encoder;
    private static final RowMapper<View> VIEW=(r,n) -> new View(r.getString("id"),r.getString("username"),r.getString("name"),r.getString("status"),
        r.getTimestamp("created_at").toInstant().toString(),r.getTimestamp("reviewed_at")==null?null:r.getTimestamp("reviewed_at").toInstant().toString(),
        r.getString("reviewer_username"),r.getString("environment_id"),r.getString("environment_name"),r.getString("rejection_reason"));
    public AccessRequestsStore(DataSource source,UsersStore users,PasswordEncoder encoder) {
        jdbc=new JdbcTemplate(source); transactions=new TransactionTemplate(new DataSourceTransactionManager(source)); this.users=users; this.encoder=encoder;
    }
    private <T> T locked(Supplier<T> operation) {
        return transactions.execute(status -> { jdbc.queryForObject("SELECT name FROM bc_lock WHERE name='users' FOR UPDATE",String.class); return operation.get(); });
    }
    public Submitted submit(String rawUsername,String rawName,String password) {
        String username=UsersStore.normalizeUsername(rawUsername), name=UsersStore.validName(rawName); UsersStore.validPassword(password);
        return locked(() -> {
            if(jdbc.queryForObject("SELECT count(*) FROM bc_user WHERE user_group='ADM'",Long.class)==0) throw new AppException(403,"Aguarde a configuração do administrador.");
            if(users.findByUsername(username).isPresent() || jdbc.queryForObject("SELECT count(*) FROM bc_access_request WHERE username=? AND status='PENDING'",Long.class,username)>0) throw AppException.conflict("Já existe usuário ou solicitação pendente para este login.");
            if(jdbc.queryForObject("SELECT count(*) FROM bc_access_request WHERE status='PENDING'",Long.class)>=1000) throw new AppException(429,"Limite de solicitações pendentes. Tente mais tarde.");
            String id=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO bc_access_request(id,username,name,password_hash,status,created_at) VALUES(CAST(? AS uuid),?,?,?,'PENDING',current_timestamp)",id,username,name,encoder.encode(password));
            return new Submitted(id,"PENDING","Solicitação enviada. Aguarde a aprovação do responsável.");
        });
    }
    private UserModels.Account reviewer(Actor actor) {
        var account=users.findById(actor.userId()).filter(UserModels.Account::enabled).orElseThrow(() -> forbidden());
        if(!account.group().equals(actor.group())) throw forbidden();
        if(!Groups.ADM.equals(account.group()) && jdbc.queryForObject("SELECT count(*) FROM bc_environment e JOIN bc_environment_member m ON m.environment_id=e.id JOIN bc_environment_permission p ON p.environment_id=e.id WHERE e.enabled AND m.user_id=CAST(? AS uuid) AND p.permission='APPROVE_USERS'",Long.class,account.id())==0) throw forbidden();
        return account;
    }
    private static AppException forbidden() { return new AppException(403,"Você não tem permissão para avaliar solicitações de acesso."); }
    public List<View> pending(Actor actor) { return locked(() -> { reviewer(actor); return jdbc.query("SELECT * FROM bc_access_request WHERE status='PENDING' ORDER BY created_at,id",VIEW); }); }
    private View find(String id) {
        try { UUID.fromString(id); } catch(RuntimeException ex) { throw new AppException(404,"Solicitação não encontrada."); }
        return jdbc.query("SELECT * FROM bc_access_request WHERE id=CAST(? AS uuid)",VIEW,id).stream().findFirst().orElseThrow(() -> new AppException(404,"Solicitação não encontrada."));
    }
    private View pendingRequest(String id) { View view=find(id); if(!"PENDING".equals(view.status())) throw AppException.conflict("Esta solicitação já foi avaliada."); return view; }
    public View approve(Actor actor,String id,String environmentId) {
        return locked(() -> {
            var reviewer=reviewer(actor);
            if(environmentId==null || environmentId.isBlank()) throw AppException.bad("Selecione o ambiente inicial.");
            try { UUID.fromString(environmentId); } catch(RuntimeException ex) { throw AppException.bad("Ambiente inválido."); }
            var names=jdbc.queryForList("SELECT name FROM bc_environment WHERE id=CAST(? AS uuid) AND enabled",String.class,environmentId);
            if(names.size()!=1) throw new AppException(403,"Ambiente indisponível para aprovação.");
            if(!Groups.ADM.equals(reviewer.group()) && jdbc.queryForObject("SELECT count(*) FROM bc_environment_member m JOIN bc_environment_permission p ON p.environment_id=m.environment_id WHERE m.environment_id=CAST(? AS uuid) AND m.user_id=CAST(? AS uuid) AND p.permission='APPROVE_USERS'",Long.class,environmentId,reviewer.id())!=1) throw forbidden();
            View request=pendingRequest(id);
            if(users.findByUsername(request.username()).isPresent()) throw AppException.conflict("Já existe um usuário com esse login.");
            String userId=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO bc_user(id,username,name,password_hash,user_group,enabled,revision,created_at,updated_at) SELECT CAST(? AS uuid),username,name,password_hash,'USER',true,1,current_timestamp,current_timestamp FROM bc_access_request WHERE id=CAST(? AS uuid)",userId,id);
            jdbc.update("INSERT INTO bc_environment_member(environment_id,user_id) VALUES(CAST(? AS uuid),CAST(? AS uuid))",environmentId,userId);
            jdbc.update("UPDATE bc_environment SET revision=revision+1,updated_at=current_timestamp WHERE id=CAST(? AS uuid)",environmentId);
            jdbc.update("UPDATE bc_access_request SET status='APPROVED',password_hash=NULL,reviewed_at=current_timestamp,reviewer_id=CAST(? AS uuid),reviewer_username=?,environment_id=CAST(? AS uuid),environment_name=?,approved_user_id=CAST(? AS uuid) WHERE id=CAST(? AS uuid)",reviewer.id(),reviewer.username(),environmentId,names.get(0),userId,id);
            return find(id);
        });
    }
    public View reject(Actor actor,String id,String rawReason) {
        return locked(() -> {
            var reviewer=reviewer(actor); pendingRequest(id);
            String reason=rawReason==null?null:rawReason.strip();
            if(reason!=null && (reason.length()>500 || reason.chars().anyMatch(Character::isISOControl))) throw AppException.bad("Informe até 500 caracteres no motivo, sem caracteres de controle.");
            jdbc.update("UPDATE bc_access_request SET status='REJECTED',password_hash=NULL,reviewed_at=current_timestamp,reviewer_id=CAST(? AS uuid),reviewer_username=?,rejection_reason=? WHERE id=CAST(? AS uuid)",reviewer.id(),reviewer.username(),reason,id);
            return find(id);
        });
    }
}
