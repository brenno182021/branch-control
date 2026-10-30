package br.com.el.branchcontrol;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class AccessRequestsIntegrationTest {
    static final TestDatabase DB = new TestDatabase();
    static final JsonMapper JSON = JsonMapper.builder().build();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",DB.source::getUrl); r.add("spring.datasource.username",DB.source::getUsername); r.add("spring.datasource.password",DB.source::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired UsersStore users;
    record Client(MockHttpSession session,String csrf) {}
    Client client() throws Exception { return client(null); }
    Client client(MockHttpSession session) throws Exception {
        var builder=get("/api/auth/session").header("X-Branch-Control","local"); if(session!=null) builder.session(session);
        var result=mvc.perform(builder).andReturn();
        return new Client((MockHttpSession)result.getRequest().getSession(),JSON.readTree(result.getResponse().getContentAsString()).get("csrfToken").asText());
    }
    String post(Client c,String path,Object body,int status) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).session(c.session()).header("X-Branch-Control","local").header("X-CSRF-TOKEN",c.csrf()).contentType("application/json").content(JSON.writeValueAsString(body))).andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
    }
    @Test void publicSubmissionRequiresCsrfAndPendingCannotLogin() throws Exception {
        users.setupAdmin("admin","Admin","Senha-Forte-123");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/access-requests").header("X-Branch-Control","local").contentType("application/json").content("{}")).andExpect(status().isForbidden());
        Client c=client();
        String response=post(c,"/api/auth/access-requests",Map.of("username"," New.User ","name","Nova Pessoa","password","Spéçial-Case!123"),202);
        assertEquals("PENDING",JSON.readTree(response).get("status").asText());
        assertFalse(response.contains("password"));
        assertTrue(users.findByUsername("new.user").isEmpty());
        post(c,"/api/auth/login",Map.of("username","new.user","password","Spéçial-Case!123"),401);
        post(c,"/api/auth/access-requests",Map.of("username","NEW.USER","name","Outro","password","Outra-Senha-123"),409);
        String hash=new JdbcTemplate(DB.source).queryForObject("SELECT password_hash FROM bc_access_request WHERE username='new.user'",String.class);
        assertTrue(hash.startsWith("$2a$12$"));
        users.create("ordinary","Ordinary","Senha-Forte-123","USER");
        Client ordinary=client(); post(ordinary,"/api/auth/login",Map.of("username","ordinary","password","Senha-Forte-123"),200); ordinary=client(ordinary.session());
        mvc.perform(get("/api/access-requests").session(ordinary.session()).header("X-Branch-Control","local")).andExpect(status().isForbidden());
        String id=JSON.readTree(response).get("id").asText();
        post(ordinary,"/api/access-requests/"+id+"/approve",Map.of("environmentId",UUID.randomUUID().toString()),403);
        post(ordinary,"/api/access-requests/"+id+"/reject",Map.of(),403);
        Client admin=client(); post(admin,"/api/auth/login",Map.of("username","admin","password","Senha-Forte-123"),200); admin=client(admin.session());
        String queue=mvc.perform(get("/api/access-requests").session(admin.session()).header("X-Branch-Control","local")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(queue.contains("password")); assertFalse(queue.contains(hash));
        String env=UUID.randomUUID().toString();
        var jdbc=new JdbcTemplate(DB.source);
        jdbc.update("INSERT INTO bc_environment(id,name,repository_path,enabled,revision) VALUES(CAST(? AS uuid),'Initial','/test',true,1)",env);
        post(admin,"/api/access-requests/"+id+"/approve",Map.of(),400);
        String approved=post(admin,"/api/access-requests/"+id+"/approve",Map.of("environmentId",env),200);
        assertFalse(approved.contains("password")); assertFalse(approved.contains(hash));
        Client approvedClient=client(); post(approvedClient,"/api/auth/login",Map.of("username","NEW.USER","password","Spéçial-Case!123"),200);
        for(int i=0;i<4;i++) post(c,"/api/auth/access-requests",Map.of("username","!","name","Bad","password","short"),400);
        post(c,"/api/auth/access-requests",Map.of("username","valid","name","Valid","password","Valid-Password-123"),429);
        for(int i=0;i<6;i++) mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/access-requests").session(c.session()).header("X-Branch-Control","local").header("X-CSRF-TOKEN",c.csrf()).with(r -> {r.setRemoteAddr("127.0.0.2");return r;}).contentType("application/json").content("{")).andExpect(status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/access-requests").session(c.session()).header("X-Branch-Control","local").header("X-CSRF-TOKEN",c.csrf()).with(r -> {r.setRemoteAddr("127.0.0.2");return r;}).contentType("application/json").content("{")).andExpect(status().isTooManyRequests());
        mvc.perform(get("/api/auth/access-requests/"+id).header("X-Branch-Control","local")).andExpect(status().isUnauthorized());
    }
}
