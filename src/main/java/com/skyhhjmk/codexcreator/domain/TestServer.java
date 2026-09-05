package com.skyhhjmk.codexcreator.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "test_servers")
public class TestServer extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false, unique = true, length = 120) public String name;
    @Column(nullable = false, length = 255) public String host;
    @Column(name = "ssh_port", nullable = false) public int sshPort = 22;
    @Column(name = "ssh_user", nullable = false, length = 64) public String sshUser = "root";
    @Column(name = "private_key_encrypted", nullable = false, columnDefinition = "text") public String privateKeyEncrypted;
    @Column(nullable = false) public boolean enabled = true;
    @Column(name = "created_at", nullable = false, updatable = false) public OffsetDateTime createdAt;
    @Column(name = "updated_at", nullable = false) public OffsetDateTime updatedAt;
}
