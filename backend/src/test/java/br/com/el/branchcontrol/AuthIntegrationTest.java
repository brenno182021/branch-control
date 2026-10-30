package br.com.el.branchcontrol;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class AuthIntegrationTest {
    static final Path ROOT = temporary();
    static final TestDatabase DATABASE = new TestDatabase();
    static final JsonMapper JSON = JsonMapper.builder().build();
    static Path temporary() { try { return Files.createTempDirectory("branch-auth-test-"); } catch (Exception ex) { throw new RuntimeException(ex); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("branch-control.workspace-root", ROOT::toString);
        registry.add("branch-control.log-directory", () -> ROOT.resolve("logs").toString());
        registry.add("spring.datasource.url", DATABASE.source::getUrl);
        registry.add("spring.datasource.username", DATABASE.source::getUsername);
        registry.add("spring.datasource.password", DATABASE.source::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired UsersStore users;
    @Autowired RunService runs;
    @Autowired GitService git;
    @Autowired ExecutionStore executions;
    @Autowired EnvironmentStore environments;

    record Client(MockHttpSession session, String csrf) {}
    MockHttpServletRequestBuilder api(MockHttpServletRequestBuilder request) { return request.header("X-Branch-Control", "local").header("Origin", "http://localhost:8087"); }
    JsonNode json(MvcResult result) throws Exception { return JSON.readTree(result.getResponse().getContentAsString()); }
    Client session(MockHttpSession session) throws Exception {
        var request = api(get("/api/auth/session"));
        if (session != null) request.session(session);
        var response = mvc.perform(request).andExpect(status().isOk()).andReturn();
        return new Client((MockHttpSession) response.getRequest().getSession(), json(response).get("csrfToken").asText());
    }
    MvcResult postAs(Client client, String path, Object value, int expected) throws Exception {
        return mvc.perform(api(post(path)).session(client.session()).with(request -> {
                    request.setRequestedSessionId(client.session().getId()); request.setRequestedSessionIdValid(true); return request;
                }).header("X-CSRF-TOKEN", client.csrf()).contentType("application/json").content(JSON.writeValueAsString(value)))
                .andExpect(status().is(expected)).andReturn();
    }
    Client login(String username) throws Exception {
        Client anonymous = session(null);
        var result = postAs(anonymous, "/api/auth/login", Map.of("username", username, "password", "Teste-local-123"), 200);
        assertFalse(result.getResponse().getContentAsString().contains("password"));
        return session((MockHttpSession) result.getRequest().getSession());
    }

    @Test void authenticatesEnforcesRolesOwnPlansCsrfReportsAndRevocation() throws Exception {
        mvc.perform(get("/index.html")).andExpect(status().isOk());
        mvc.perform(get("/assets/app.js")).andExpect(status().isOk());
        mvc.perform(api(get("/api/runs"))).andExpect(status().isUnauthorized());
        mvc.perform(api(get("/api/admin/users"))).andExpect(status().isUnauthorized());
        mvc.perform(api(post("/api/auth/setup")).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        Client first = session(null);
        var setupBody = Map.of("username", "admin", "name", "Administrador", "password", "Teste-local-123");
        mvc.perform(api(post("/api/auth/setup")).session(first.session()).header("X-CSRF-TOKEN", first.csrf())
                .with(request -> { request.setRemoteAddr("192.168.1.25"); return request; })
                .contentType("application/json").content(JSON.writeValueAsString(setupBody))).andExpect(status().isForbidden());
        String oldSession = first.session().getId();
        var setup = postAs(first, "/api/auth/setup", setupBody, 200);
        assertNotEquals(oldSession, setup.getRequest().getSession().getId());
        Client admin = session((MockHttpSession) setup.getRequest().getSession());
        assertFalse(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(users.findByUsername("admin").orElseThrow()).contains("Teste-local-123"));
        postAs(admin, "/api/auth/setup", setupBody, 409);
        for (String name : List.of("dev", "outro", "teste")) {
            postAs(admin, "/api/admin/users", Map.of("username", name, "name", name, "password", "Teste-local-123", "group", name.equals("teste") ? Groups.USER : Groups.USER), 200);
        }
        Client dev = login("dev"), other = login("outro"), tester = login("teste");
        mvc.perform(api(get("/api/admin/users")).session(dev.session())).andExpect(status().isForbidden());
        mvc.perform(api(get("/api/config")).session(tester.session())).andExpect(status().isForbidden());
        postAs(tester, "/api/plans", Map.of(), 400);
        postAs(dev, "/api/admin/environments", Map.of(), 403);
        mvc.perform(api(get("/data/users.json")).session(admin.session())).andExpect(status().isForbidden());
        mvc.perform(api(get("/api/runs")).session(admin.session()).with(request -> { request.setRemoteAddr("203.0.113.40"); return request; })).andExpect(status().isForbidden());
        mvc.perform(get("/api/runs").header("X-Branch-Control", "local").header("Origin", "https://external.example").session(admin.session())).andExpect(status().isForbidden());

        Path repo = Files.createDirectory(ROOT.resolve("repo"));
        GitRunner runner = new GitRunner(30);
        runner.require(repo, "init", "-q", "--initial-branch=destino");
        runner.require(repo, "config", "user.name", "Teste"); runner.require(repo, "config", "user.email", "teste@example.invalid");
        runner.require(repo, "config", "commit.gpgsign", "false");
        runner.require(repo, "config", "core.autocrlf", "false");
        runner.require(repo, "config", "core.hooksPath", ROOT.resolve("no-hooks").toString());
        Files.writeString(repo.resolve("arquivo.txt"), "base\n"); runner.require(repo, "add", "."); runner.require(repo, "commit", "-qm", "base");
        String base = runner.require(repo, "rev-parse", "HEAD").output().strip();
        runner.require(repo, "branch", "secret/deploy");
        runner.require(repo, "switch", "-qc", "origem");
        Files.writeString(repo.resolve("arquivo.txt"), "alterado\n"); runner.require(repo, "add", "."); runner.require(repo, "commit", "-qm", "=mensagem CSV");
        String hash = runner.require(repo, "rev-parse", "HEAD").output().strip(); runner.require(repo, "switch", "-q", "destino");
        String devId=users.findByUsername("dev").orElseThrow().id(), otherId=users.findByUsername("outro").orElseThrow().id(), testerId=users.findByUsername("teste").orElseThrow().id();
        var envBody=Map.of("name","Desenvolvimento","repositoryPath",repo.toString(),"branchPatterns",List.of("origem","destino"),"permissions",List.of("PREPARE","SIMULATE","APPLY"),"userIds",List.of(devId,otherId),"enabled",true);
        String environmentId=json(postAs(admin,"/api/admin/environments",envBody,200)).get("id").asText();
        var reportBody=Map.of("name","Consulta","repositoryPath",repo.toString(),"branchPatterns",List.of("origem","destino"),"permissions",List.of("REPORTS"),"userIds",List.of(testerId),"enabled",true);
        String reportEnvironment=json(postAs(admin,"/api/admin/environments",reportBody,200)).get("id").asText();
        postAs(tester,"/api/plans",Map.of("environmentId",environmentId,"sourceBranch","origem","targetBranch","destino","cutoff","2099-01-01"),403);
        postAs(dev,"/api/repositories/fetch",Map.of("environmentId",environmentId),403);
        postAs(dev,"/api/repositories/switch",Map.of("environmentId",environmentId,"targetBranch","destino"),403);
        postAs(dev,"/api/plans",Map.of("environmentId",environmentId,"sourceBranch","secret/deploy","targetBranch","destino","cutoff","2099-01-01"),403);
        postAs(dev,"/api/plans",Map.of("environmentId",environmentId,"sourceBranch","origem","targetBranch","secret/deploy","cutoff","2099-01-01"),403);
        var filtered=json(postAs(dev,"/api/repositories/open",Map.of("environmentId",environmentId),200));
        assertFalse(filtered.toString().contains("secret/deploy"));
        var preview = json(postAs(dev, "/api/plans", Map.of("environmentId",environmentId, "sourceBranch", "origem", "targetBranch", "destino", "cutoff", "2099-01-01"), 200));
        String id = preview.get("id").asText();
        var selection = Map.of("selectedHashes", List.of(hash), "simulation", false);
        postAs(other, "/api/plans/" + id + "/runs", selection, 404);
        postAs(dev, "/api/plans/" + id + "/runs", selection, 403);
        var simulation = json(postAs(dev, "/api/plans/" + id + "/runs", Map.of("selectedHashes", List.of(hash), "simulation", true), 202));
        String simulationId = simulation.get("id").asText();
        await(simulationId);
        assertEquals(base, runner.require(repo, "rev-parse", "HEAD").output().strip());
        var execution = json(postAs(dev, "/api/plans/" + id + "/runs", selection, 202)); await(execution.get("id").asText());
        assertEquals("alterado\n", Files.readString(repo.resolve("arquivo.txt")));
        Models.Actor adminActor = users.findByUsername("admin").orElseThrow().actor();
        // A separate ADM simulation is visible only to ADM, never TESTE or another DEV.
        var oldPlan = executions.load().stream().filter(entry -> entry.run().id().equals(simulationId)).findFirst().orElseThrow().plan();
        var admRun = runs.start(oldPlan, List.of(hash), true, adminActor); await(admRun.id());
        mvc.perform(api(get("/api/runs/" + admRun.id())).session(tester.session())).andExpect(status().isOk());
        mvc.perform(api(get("/api/runs/" + simulationId + "/log")).session(other.session())).andExpect(status().isNotFound());
        var report = mvc.perform(api(get("/api/reports")).session(tester.session())).andExpect(status().isOk()).andReturn();
        assertEquals(3, json(report).get("total").asInt()); assertEquals(1, json(report).get("applied").asInt());
        var csv = mvc.perform(api(get("/api/reports.csv")).session(tester.session())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(csv.contains("'=mensagem CSV")); assertTrue(csv.contains("admin"));
        try (RunService restored = new RunService(git, new AppProperties(ROOT.toString(), ROOT.resolve("logs").toString(), "America/Sao_Paulo", 30), executions,environments)) {
            assertEquals(3, restored.list(users.findByUsername("teste").orElseThrow().actor()).size());
            assertEquals("COMPLETED", restored.get(simulationId, adminActor).state());
        }
        // Report access is scoped by repository AND the current branch masks.
        var adminActorForChange=users.findByUsername("admin").orElseThrow().actor();
        environments.save(reportEnvironment,new EnvironmentStore.Change("Consulta",repo.toString(),List.of("secret/*"),List.of("REPORTS"),List.of(testerId),true),adminActorForChange);
        var hidden=mvc.perform(api(get("/api/reports")).session(tester.session())).andExpect(status().isOk()).andReturn();
        assertEquals(0,json(hidden).get("total").asInt());
        mvc.perform(api(get("/api/runs/"+simulationId+"/log")).session(tester.session())).andExpect(status().isNotFound());
        environments.save(environmentId,new EnvironmentStore.Change("Desenvolvimento",repo.toString(),List.of("destino"),List.of("PREPARE","SIMULATE","APPLY"),List.of(devId,otherId),true),adminActorForChange);
        postAs(dev,"/api/plans/"+id+"/export",selection,409);
        var account = users.findByUsername("dev").orElseThrow();
        users.update(account.id(), account.name(), account.group(), false, null);
        mvc.perform(api(get("/api/runs")).session(dev.session())).andExpect(status().isUnauthorized());
        postAs(tester, "/api/auth/logout", Map.of(), 200);
        mvc.perform(api(get("/api/runs"))).andExpect(status().isUnauthorized());
    }
    void await(String id) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < until) {
            var run = runs.get(id);
            if (!List.of("QUEUED", "RUNNING").contains(run.state())) { assertEquals("COMPLETED", run.state(), String.join("\n", run.lines())); return; }
            Thread.sleep(50);
        }
        fail("Tempo limite da execução.");
    }
}
