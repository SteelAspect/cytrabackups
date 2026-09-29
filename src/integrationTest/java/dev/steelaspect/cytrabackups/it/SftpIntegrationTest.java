package dev.steelaspect.cytrabackups.it;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.KeyPair;
import dev.steelaspect.cytrabackups.core.CancelToken;
import dev.steelaspect.cytrabackups.core.Progress;
import dev.steelaspect.cytrabackups.core.backup.BackupMeta;
import dev.steelaspect.cytrabackups.core.offsite.SftpTarget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.pubkey.AcceptAllPublickeyAuthenticator;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the SFTP target against an embedded Apache MINA SSHD server. */
class SftpIntegrationTest {
	@TempDir
	Path dir;

	private SshServer server(Path root, Path hostKey, int port, boolean passwordOnly) throws IOException {
		SshServer sshd = SshServer.setUpDefaultServer();
		sshd.setHost("127.0.0.1");
		sshd.setPort(port);
		sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKey));
		sshd.setPasswordAuthenticator((user, pass, session) -> user.equals("backup") && pass.equals("s3cret"));
		sshd.setPublickeyAuthenticator(passwordOnly ? null : AcceptAllPublickeyAuthenticator.INSTANCE);
		sshd.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
		sshd.setFileSystemFactory(new VirtualFileSystemFactory(root));
		sshd.start();
		return sshd;
	}

	private SftpTarget target(int port, String password, Path key, String checking, Path knownHosts) {
		return new SftpTarget("127.0.0.1", port, "backup", password, key, "", "remote/backups", checking, knownHosts);
	}

	@Test
	void passwordUploadExistsDelete() throws Exception {
		Path root = Files.createDirectories(dir.resolve("sftp-root"));
		SshServer sshd = server(root, dir.resolve("host.ser"), 0, true);
		try (SftpTarget t = target(sshd.getPort(), "s3cret", null, "tofu", dir.resolve("known_hosts"))) {
			Path f = dir.resolve("f.bin");
			Files.writeString(f, "sftp payload");
			assertFalse(t.exists("a/b/c.bin"));
			t.upload("a/b/c.bin", f);
			assertTrue(t.exists("a/b/c.bin"));
			assertArrayEquals("sftp payload".getBytes(), Files.readAllBytes(root.resolve("remote/backups/a/b/c.bin")));
			assertFalse(Files.exists(root.resolve("remote/backups/a/b/c.bin.part")), "temp file renamed into place");
			t.upload("a/b/c.bin", f); // overwrite works
			t.delete("a/b/c.bin");
			assertFalse(t.exists("a/b/c.bin"));
			t.delete("a/b/c.bin"); // idempotent
		} finally {
			sshd.stop(true);
		}
	}

	@Test
	void wrongPasswordFails() throws Exception {
		SshServer sshd = server(Files.createDirectories(dir.resolve("r")), dir.resolve("host.ser"), 0, true);
		try (SftpTarget t = target(sshd.getPort(), "wrong", null, "no", dir.resolve("kh"))) {
			assertThrows(IOException.class, () -> t.exists("x"));
		} finally {
			sshd.stop(true);
		}
	}

	@Test
	void privateKeyAuthentication() throws Exception {
		Path key = dir.resolve("id_rsa");
		KeyPair kp = KeyPair.genKeyPair(new JSch(), KeyPair.RSA, 2048);
		kp.writePrivateKey(key.toString());
		kp.dispose();
		SshServer sshd = server(Files.createDirectories(dir.resolve("r")), dir.resolve("host.ser"), 0, false);
		try (SftpTarget t = target(sshd.getPort(), "", key, "no", dir.resolve("kh"))) {
			Path f = dir.resolve("f.bin");
			Files.writeString(f, "key auth");
			t.upload("k.bin", f);
			assertTrue(t.exists("k.bin"));
		} finally {
			sshd.stop(true);
		}
	}

	@Test
	void trustOnFirstUseThenRejectChangedHostKey() throws Exception {
		Path root = Files.createDirectories(dir.resolve("r"));
		Path knownHosts = dir.resolve("offsite/known_hosts");
		SshServer first = server(root, dir.resolve("hostA.ser"), 0, true);
		int port = first.getPort();
		try (SftpTarget t = target(port, "s3cret", null, "tofu", knownHosts)) {
			assertFalse(t.exists("x"));
		}
		String pinned = Files.readString(knownHosts);
		assertTrue(pinned.contains("127.0.0.1"), "host key pinned on first use: " + pinned);
		try (SftpTarget t = target(port, "s3cret", null, "tofu", knownHosts)) {
			assertFalse(t.exists("x"), "same key is accepted again");
		}
		first.stop(true);

		SshServer impostor = server(root, dir.resolve("hostB.ser"), port, true); // new host key, same address
		try (SftpTarget t = target(port, "s3cret", null, "tofu", knownHosts)) {
			IOException e = assertThrows(IOException.class, () -> t.exists("x"));
			assertTrue(e.getMessage().toLowerCase().contains("changed") || e.getMessage().toLowerCase().contains("reject"), e.getMessage());
		}
		try (SftpTarget t = target(port, "s3cret", null, "yes", dir.resolve("empty_known_hosts"))) {
			assertThrows(IOException.class, () -> t.exists("x"), "strict mode refuses unknown hosts");
		}
		try (SftpTarget t = target(port, "s3cret", null, "no", dir.resolve("unused"))) {
			assertFalse(t.exists("x"), "checking disabled accepts any key");
		} finally {
			impostor.stop(true);
		}
	}

	@Test
	void offsiteSyncOverSftp() throws Exception {
		Path root = Files.createDirectories(dir.resolve("r"));
		SshServer sshd = server(root, dir.resolve("host.ser"), 0, true);
		try (Fixture fx = new Fixture(dir.resolve("fx")); SftpTarget t = target(sshd.getPort(), "s3cret", null, "tofu", dir.resolve("kh"))) {
			BackupMeta a = fx.backup("a");
			fx.sync.enqueueUpload(a.id);
			fx.sync.process(t, fx.workers, false, new Progress(), CancelToken.NONE);
			Set<String> remote = new TreeSet<>();
			Path base = root.resolve("remote/backups");
			try (Stream<Path> s = Files.walk(base)) {
				s.filter(Files::isRegularFile).forEach(p -> remote.add(base.relativize(p).toString().replace('\\', '/')));
			}
			assertEquals(new TreeSet<>(fx.expectedKeys(a)), remote);
		} finally {
			sshd.stop(true);
		}
	}
}
