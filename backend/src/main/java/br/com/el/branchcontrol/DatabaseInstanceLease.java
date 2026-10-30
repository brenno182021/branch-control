package br.com.el.branchcontrol;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.slf4j.*;
import jakarta.annotation.PreDestroy;
import javax.sql.DataSource;
import java.sql.*;
import java.util.concurrent.*;

@Component
@DependsOnDatabaseInitialization
public final class DatabaseInstanceLease implements AutoCloseable {
    private static final Logger LOG=LoggerFactory.getLogger(DatabaseInstanceLease.class);
    private final Connection connection;
    private final ConfigurableApplicationContext context;
    private final ScheduledExecutorService monitor=Executors.newSingleThreadScheduledExecutor(task -> {var thread=new Thread(task,"database-instance-lease");thread.setDaemon(true);return thread;});
    private final int processId;
    private final CopyOnWriteArrayList<Runnable> lossHandlers=new CopyOnWriteArrayList<>();
    private boolean lost,closed;
    DatabaseInstanceLease(DataSource source) throws SQLException {this(source,null);}
    @Autowired public DatabaseInstanceLease(DataSource source,ConfigurableApplicationContext context) throws SQLException {
        this.context=context; connection=source.getConnection();
        try (var statement=connection.createStatement()) {
            statement.setQueryTimeout(3);
            try(var result=statement.executeQuery("SELECT pg_backend_pid(),pg_try_advisory_lock(hashtext(current_schema()),1820218087)")) {
                if(!result.next()||!result.getBoolean(2)) throw new IllegalStateException("Já existe uma instância do Branch Control usando este banco/schema. Use um banco exclusivo por servidor Git.");
                processId=result.getInt(1);
            }
        }catch(SQLException|RuntimeException ex){connection.close();monitor.shutdownNow();throw ex;}
        monitor.scheduleWithFixedDelay(() -> {try{verify();}catch(RuntimeException ignored){}},1,1,TimeUnit.SECONDS);
    }
    public synchronized void verify() {
        if(lost||closed) throw new AppException(503,"A exclusividade da instância foi perdida. Reinicie o aplicativo e confira git status antes de tentar novamente.");
        try(var statement=connection.createStatement()) {
            statement.setQueryTimeout(2);
            try(var result=statement.executeQuery("SELECT EXISTS(SELECT 1 FROM pg_locks WHERE locktype='advisory' AND pid=pg_backend_pid() AND objid=1820218087 AND objsubid=2 AND granted)")) {
                if(!result.next()||!result.getBoolean(1)) throw new SQLException("Lease não pertence mais à sessão.");
            }
        }catch(SQLException ex){
            lost=true; monitor.shutdownNow();
            LOG.error("Conexão de exclusividade PostgreSQL perdida; encerrando a instância e interrompendo tarefas Git. Confira git status.");
            for(var handler:lossHandlers)try{handler.run();}catch(RuntimeException failure){LOG.error("Falha ao cancelar tarefa durante perda de exclusividade.",failure);}
            if(context!=null){var shutdown=new Thread(context::close,"lease-fail-stop");shutdown.setDaemon(true);shutdown.start();}
            throw new AppException(503,"Conexão de exclusividade perdida. A instância foi bloqueada e está encerrando; confira git status.");
        }
    }
    int processId(){return processId;}
    public synchronized void onLoss(Runnable handler){if(lost||closed)handler.run();else lossHandlers.add(handler);}
    @Override @PreDestroy public synchronized void close() {
        if(closed)return; closed=true; monitor.shutdownNow();
        if(!lost) try(var statement=connection.createStatement()){statement.setQueryTimeout(2);statement.execute("SELECT pg_advisory_unlock(hashtext(current_schema()),1820218087)");}catch(SQLException ignored){}
        try{connection.close();}catch(SQLException ignored){}
    }
}
