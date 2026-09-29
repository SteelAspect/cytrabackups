package dev.steelaspect.cytrabackups.core.offsite;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.UserInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** SFTP target using the pure-Java JSch library. One session is reused and reconnected on demand. */
public final class SftpTarget implements OffsiteTarget {
	private final String host;
	private final int port;
	private final String user;
	private final String password;
	private final Path privateKey;
	private final String passphrase;
	private final String remoteDir;
	private final String hostKeyChecking;
	private final Path knownHosts;
	private final Set<String> knownDirs = ConcurrentHashMap.newKeySet();
	private Session session;
	private ChannelSftp channel;

	public SftpTarget(String host, int port, String user, String password, Path privateKey, String passphrase, String remoteDir,
					  String hostKeyChecking, Path knownHosts) {
		if (host.isBlank() || user.isBlank()) throw new IllegalArgumentException("offsite.sftp.host and username are required");
		this.host = host;
		this.port = port;
		this.user = user;
		this.password = password;
		this.privateKey = privateKey;
		this.passphrase = passphrase;
		String dir = remoteDir.replace('\\', '/');
		this.remoteDir = dir.endsWith("/") ? dir.substring(0, dir.length() - 1) : dir;
		this.hostKeyChecking = hostKeyChecking;
		this.knownHosts = knownHosts;
	}

	private synchronized ChannelSftp channel() throws IOException {
		try {
			if (channel != null && channel.isConnected() && session.isConnected()) return channel;
			close();
			JSch jsch = new JSch();
			if (!hostKeyChecking.equals("no")) {
				Files.createDirectories(knownHosts.toAbsolutePath().getParent());
				if (!Files.exists(knownHosts)) Files.createFile(knownHosts);
				jsch.setKnownHosts(knownHosts.toString());
			}
			if (privateKey != null) {
				if (passphrase.isEmpty()) jsch.addIdentity(privateKey.toString());
				else jsch.addIdentity(privateKey.toString(), passphrase);
			}
			Session s = jsch.getSession(user, host, port);
			if (!password.isEmpty()) s.setPassword(password);
			s.setConfig("StrictHostKeyChecking", switch (hostKeyChecking) {
				case "no" -> "no";
				case "yes" -> "yes";
				default -> "ask";
			});
			s.setUserInfo(new TofuUserInfo(password, passphrase));
			s.setServerAliveInterval(30_000);
			s.connect(20_000);
			ChannelSftp c = (ChannelSftp) s.openChannel("sftp");
			c.connect(20_000);
			session = s;
			channel = c;
			return c;
		} catch (JSchException e) {
			throw new IOException("SFTP connection to " + host + " failed: " + e.getMessage(), e);
		}
	}

	@Override
	public synchronized void upload(String key, Path file) throws IOException {
		String remote = remoteDir + "/" + key;
		ChannelSftp c = channel();
		try {
			mkdirs(c, remote.substring(0, remote.lastIndexOf('/')));
			String tmp = remote + ".part";
			c.put(file.toString(), tmp);
			try {
				c.rm(remote);
			} catch (SftpException ignored) {
			}
			c.rename(tmp, remote);
		} catch (SftpException e) {
			throw new IOException("SFTP upload of " + key + " failed: " + e.getMessage(), e);
		}
	}

	private void mkdirs(ChannelSftp c, String dir) throws SftpException {
		if (dir.isEmpty() || knownDirs.contains(dir)) return;
		StringBuilder path = new StringBuilder(dir.startsWith("/") ? "/" : "");
		for (String part : dir.split("/")) {
			if (part.isEmpty()) continue;
			path.append(part);
			String p = path.toString();
			if (!knownDirs.contains(p)) {
				try {
					c.stat(p);
				} catch (SftpException e) {
					c.mkdir(p);
				}
				knownDirs.add(p);
			}
			path.append('/');
		}
	}

	@Override
	public synchronized boolean exists(String key) throws IOException {
		try {
			channel().stat(remoteDir + "/" + key);
			return true;
		} catch (SftpException e) {
			if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return false;
			throw new IOException("SFTP stat failed: " + e.getMessage(), e);
		}
	}

	@Override
	public synchronized void delete(String key) throws IOException {
		try {
			channel().rm(remoteDir + "/" + key);
		} catch (SftpException e) {
			if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw new IOException("SFTP delete failed: " + e.getMessage(), e);
		}
	}

	@Override
	public String describe() {
		return "sftp://" + user + "@" + host + ":" + port + "/" + remoteDir;
	}

	@Override
	public String id() {
		return "sftp|" + describe();
	}

	@Override
	public synchronized void close() {
		if (channel != null) channel.disconnect();
		if (session != null) session.disconnect();
		channel = null;
		session = null;
	}

	/** Accepts unknown host keys once (trust on first use) but refuses changed keys. */
	private record TofuUserInfo(String password, String passphrase) implements UserInfo {
		@Override
		public String getPassphrase() {
			return passphrase;
		}

		@Override
		public String getPassword() {
			return password;
		}

		@Override
		public boolean promptPassword(String message) {
			return !password.isEmpty();
		}

		@Override
		public boolean promptPassphrase(String message) {
			return !passphrase.isEmpty();
		}

		@Override
		public boolean promptYesNo(String message) {
			String m = message.toLowerCase(java.util.Locale.ROOT);
			return !m.contains("changed") && !m.contains("man-in-the-middle");
		}

		@Override
		public void showMessage(String message) {
		}
	}
}
