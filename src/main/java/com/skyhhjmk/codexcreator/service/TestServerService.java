package com.skyhhjmk.codexcreator.service;

import com.skyhhjmk.codexcreator.domain.TestServer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.concurrent.TimeUnit;
import java.util.*;

/** Stores SSH credentials encrypted at rest; views deliberately never contain private keys. */
@ApplicationScoped
public class TestServerService {
    @ConfigProperty(name = "codex.creator.test-server.encryption-secret", defaultValue = "") String encryptionSecret;
    @Inject ArticleEvidenceService articleEvidence;

    public List<Map<String,Object>> list() { return TestServer.<TestServer>list("order by name").stream().map(this::view).toList(); }
    public Map<String,Object> setupGuide() { return Map.of("system", "Ubuntu 22.04 LTS 或 Debian 12", "steps", List.of(
            "安装系统后，以 root 登录并执行：apt-get update && apt-get install -y openssh-server",
            "在 Codex Creator 所在主机生成专用密钥：ssh-keygen -t ed25519 -f windblog-test-server -C windblog-codex",
            "将 windblog-test-server.pub 的内容追加到测试机 /root/.ssh/authorized_keys，然后把私钥填写到下方。"),
            "warning", "仅添加可随时重装的测试服务器；AI 会在被选中的服务器上执行教程验证命令。"); }
    @Transactional public Map<String,Object> save(Long id, Map<String,Object> input) {
        String name = text(input,"name"), host = text(input,"host"), key = text(input,"privateKey");
        if (name.isBlank() || host.isBlank()) throw new BadRequestException("name and host are required");
        TestServer server = id == null ? new TestServer() : Optional.ofNullable(TestServer.<TestServer>findById(id)).orElseThrow(() -> new NotFoundException("test server not found"));
        server.name=name; server.host=host; server.sshPort=integer(input,"sshPort",22); server.sshUser=optional(input,"sshUser","root"); server.enabled=bool(input,"enabled",true);
        if (server.sshPort < 1 || server.sshPort > 65535) throw new BadRequestException("invalid SSH port");
        if (id == null && key.isBlank()) throw new BadRequestException("privateKey is required");
        if (!key.isBlank()) server.privateKeyEncrypted=encrypt(key);
        server.updatedAt=OffsetDateTime.now(); if(id==null){server.createdAt=server.updatedAt;server.persist();} return view(server);
    }
    @Transactional public void delete(Long id) { Optional.ofNullable(TestServer.<TestServer>findById(id)).orElseThrow(() -> new NotFoundException("test server not found")).delete(); }
    public TestServer enabled(Long id) { TestServer s=TestServer.findById(id); if(s==null||!s.enabled) throw new BadRequestException("test server is unavailable"); return s; }
    public String privateKey(TestServer server) { return decrypt(server.privateKeyEncrypted); }
    /** Executes only on a server explicitly bound to this article job. Credentials never leave this service. */
    @Transactional
    public Map<String, Object> executeForArticleJob(Long articleJobId, Long serverId, String command) {
        if (articleJobId == null || serverId == null) throw new BadRequestException("articleJobId and serverId are required");
        if (command == null || command.isBlank() || command.length() > 8_000) throw new BadRequestException("a command up to 8000 characters is required");
        com.skyhhjmk.codexcreator.domain.AiArticleJob job = com.skyhhjmk.codexcreator.domain.AiArticleJob.findById(articleJobId);
        if (job == null || !job.requiresPracticalVerification) throw new BadRequestException("article job is not approved for practical verification");
        TestServer server = job.testServers.stream().filter(candidate -> Objects.equals(candidate.id, serverId)).findFirst()
                .orElseThrow(() -> new BadRequestException("server is not assigned to this article job"));
        if (!server.enabled) throw new BadRequestException("test server is unavailable");
        Path keyFile = null;
        try {
            keyFile = Files.createTempFile("windblog-test-server-", ".key");
            Files.writeString(keyFile, privateKey(server), StandardCharsets.UTF_8);
            try { Files.setPosixFilePermissions(keyFile, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)); } catch (UnsupportedOperationException ignored) { }
            Process process = new ProcessBuilder("ssh", "-i", keyFile.toString(), "-p", String.valueOf(server.sshPort),
                    "-o", "BatchMode=yes", "-o", "ConnectTimeout=15", "-o", "StrictHostKeyChecking=accept-new",
                    server.sshUser + "@" + server.host, command).redirectErrorStream(true).start();
            boolean completed = process.waitFor(90, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                String output = "SSH command timed out after 90 seconds";
                articleEvidence.recordVerification(articleJobId, serverId, command, -1, output);
                return Map.of("ok", false, "exitCode", -1, "output", output);
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            output = ArticleEvidenceService.sanitize(output, 2_000);
            int exitCode = process.exitValue();
            articleEvidence.recordVerification(articleJobId, serverId, command, exitCode, output);
            return Map.of("ok", exitCode == 0, "exitCode", exitCode, "output", output);
        } catch (Exception exception) { throw new BadRequestException("SSH verification failed: " + exception.getMessage(), exception); }
        finally { if (keyFile != null) try { Files.deleteIfExists(keyFile); } catch (Exception ignored) { } }
    }
    public Map<String,Object> view(TestServer s){return Map.of("id",s.id,"name",s.name,"host",s.host,"sshPort",s.sshPort,"sshUser",s.sshUser,"enabled",s.enabled,"privateKeyConfigured",s.privateKeyEncrypted!=null&&!s.privateKeyEncrypted.isBlank());}
    private String encrypt(String clear){try{byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key(),new GCMParameterSpec(128,iv));return Base64.getEncoder().encodeToString(iv)+":"+Base64.getEncoder().encodeToString(c.doFinal(clear.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new BadRequestException("cannot protect test-server private key",e);}}
    private String decrypt(String value){try{String[] p=value.split(":",2);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.getDecoder().decode(p[0])));return new String(c.doFinal(Base64.getDecoder().decode(p[1])),StandardCharsets.UTF_8);}catch(Exception e){throw new IllegalStateException("cannot decrypt test-server private key",e);}}
    private SecretKeySpec key(){if(encryptionSecret==null||encryptionSecret.isBlank())throw new BadRequestException("CODEx Creator test-server encryption secret is not configured");try{return new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(encryptionSecret.getBytes(StandardCharsets.UTF_8)),"AES");}catch(Exception e){throw new IllegalStateException(e);}}
    private static String text(Map<String,Object> m,String n){return m==null||m.get(n)==null?"":String.valueOf(m.get(n)).trim();} private static String optional(Map<String,Object>m,String n,String d){String v=text(m,n);return v.isBlank()?d:v;} private static int integer(Map<String,Object>m,String n,int d){try{return m!=null&&m.get(n)!=null?Integer.parseInt(String.valueOf(m.get(n))):d;}catch(Exception e){throw new BadRequestException("invalid "+n);}} private static boolean bool(Map<String,Object>m,String n,boolean d){return m==null||m.get(n)==null?d:Boolean.parseBoolean(String.valueOf(m.get(n)));}
}
