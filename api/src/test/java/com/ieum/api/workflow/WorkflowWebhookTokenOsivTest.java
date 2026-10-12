package com.ieum.api.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.ieum.api.NoRedisTestConfig;
import com.ieum.workflowcore.service.WorkflowWebhookTokenService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionImplementor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 수신 주소는 토큰 조회 뒤 본문(최대 5MB)을 클라이언트 속도로 읽는다. OSIV 아래에서 토큰 조회가
 * 커넥션을 붙잡으면 느린 업로드로 풀이 고갈된다 — 조회 뒤 OSIV 세션이 커넥션을 쥐고 있지 않아야 한다.
 */
@SpringBootTest
@Import(NoRedisTestConfig.class)
@TestPropertySource(properties = "aes.secret-key=ieum-test-secret-key-32bytes-ok!")
class WorkflowWebhookTokenOsivTest {

    @Autowired private WorkflowWebhookTokenService service;
    @Autowired private EntityManagerFactory emf;

    @Test
    @DisplayName("OSIV 세션이 묶인 상태에서 토큰 조회 뒤 커넥션을 쥐고 있지 않다")
    void lookupDoesNotHoldOsivConnection() {
        EntityManager em = emf.createEntityManager();
        TransactionSynchronizationManager.bindResource(emf, new EntityManagerHolder(em));
        try {
            assertThat(service.findWorkflowIdByToken("unknown-token")).isEmpty();
            assertThat(em.unwrap(SessionImplementor.class).getJdbcCoordinator().getLogicalConnection().isPhysicallyConnected()).isFalse();
        } finally {
            TransactionSynchronizationManager.unbindResource(emf);
            em.close();
        }
    }
}
