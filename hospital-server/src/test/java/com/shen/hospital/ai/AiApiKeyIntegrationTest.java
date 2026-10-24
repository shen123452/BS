package com.shen.hospital.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shen.hospital.auth.domain.Role;
import com.shen.hospital.auth.security.JwtService;
import com.shen.hospital.auth.service.AuthService;
import com.shen.hospital.common.BusinessException;
import javax.sql.DataSource;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"hospital.jwt.secret=test-only-signing-key-at-least-32-bytes-long", "hospital.bootstrap.enabled=false", "hospital.ai.api-key="})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="MYSQL_PASSWORD",matches=".+")
@Transactional(transactionManager="mysqlTransactionManager")
class AiApiKeyIntegrationTest {
    @Autowired AiApiKeyStore store;
    @Autowired AiProperties properties;
    @Autowired @Qualifier("mysqlDataSource") DataSource dataSource;
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;

    @Test void savedKeyIsReadByAnotherStoreWithoutAnEnvironmentKey() {
        store.save("test-only-database-key",null);
        var restartedStore = new AiApiKeyStore(dataSource,properties);
        var restartedFactory = new ModelFactory(properties,restartedStore);
        assertEquals("test-only-database-key",restartedStore.apiKey());
        assertTrue(restartedFactory.configured());
        assertTrue(properties.apiKey().isEmpty());
    }

    @Test void keyRotationRefreshesAllModelClientsWithoutChangingVectorSpace() {
        store.save("test-only-first-key",null);
        var factory = new ModelFactory(properties,store);
        var chat = factory.chat(); var stream = factory.stream(); var embedding = factory.embedding();
        String fingerprint = factory.fingerprint();
        store.save("test-only-second-key",null);
        assertNotSame(chat,factory.chat()); assertNotSame(stream,factory.stream()); assertNotSame(embedding,factory.embedding());
        assertEquals(fingerprint,factory.fingerprint());
        assertSame(factory.chat(),factory.chat());
    }

    @Test void environmentImportDoesNotOverwriteAnExistingDatabaseKey() {
        store.save("test-only-stored-key",null);
        var environmentConfig = new AiProperties(properties.baseUrl(),"test-only-environment-key",properties.chatModel(),properties.embeddingModel(),properties.embeddingDimension(),properties.timeoutSeconds());
        new AiApiKeyStore(dataSource,environmentConfig).importEnvironmentKey();
        assertEquals("test-only-stored-key",store.apiKey());
        assertFalse(environmentConfig.toString().contains("test-only-environment-key"));
    }

    @Test void invalidKeyCannotReplaceTheStoredCredential() {
        store.save("test-only-valid-key",null);
        assertThrows(BusinessException.class,() -> store.save("short",null));
        assertThrows(BusinessException.class,() -> store.save("test-only-key\nInjected: header",null));
        assertEquals("test-only-valid-key",store.apiKey());
    }

    @Test void adminCanSaveButNoResponseEchoesTheSecret() throws Exception {
        var user = auth.createUser("key_test_"+UUID.randomUUID().toString().substring(0,8),"TestPassword123!","配置测试管理员",Role.ADMIN);
        String token = jwt.issue(user.getId());
        String secret = "test-only-http-key";
        var response = mvc.perform(put("/api/admin/ai/api-key").header("Authorization","Bearer "+token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(java.util.Map.of("apiKey",secret))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.configured").value(true))
                .andExpect(jsonPath("$.data.credentialSource").value("DATABASE"))
                .andExpect(jsonPath("$.data.apiKey").doesNotExist()).andReturn();
        assertFalse(response.getResponse().getContentAsString().contains(secret));
        mvc.perform(get("/api/admin/ai/config").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.apiKey").doesNotExist());
        assertEquals(secret,store.apiKey());
    }

    @Test void patientDoctorAndAnonymousCannotReadOrReplaceTheKey() throws Exception {
        store.save("test-only-protected-key",null);
        for (var role : java.util.List.of(Role.PATIENT,Role.DOCTOR)) {
            var user = auth.createUser("key_role_"+UUID.randomUUID().toString().substring(0,8),"TestPassword123!","权限测试",role);
            String token = jwt.issue(user.getId());
            mvc.perform(get("/api/admin/ai/config").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
            mvc.perform(put("/api/admin/ai/api-key").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"apiKey\":\"test-only-not-allowed\"}")).andExpect(status().isForbidden());
        }
        mvc.perform(put("/api/admin/ai/api-key").contentType(MediaType.APPLICATION_JSON).content("{\"apiKey\":\"test-only-not-allowed\"}"))
                .andExpect(status().isUnauthorized());
        assertEquals("test-only-protected-key",store.apiKey());
    }
}
