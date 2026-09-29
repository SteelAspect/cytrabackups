package dev.steelaspect.cytrabackups.it;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.steelaspect.cytrabackups.core.CancelToken;
import dev.steelaspect.cytrabackups.core.Progress;
import dev.steelaspect.cytrabackups.core.backup.BackupMeta;
import dev.steelaspect.cytrabackups.core.offsite.WebDavTarget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.authenticator.BasicAuthenticator;
import org.apache.catalina.servlets.WebdavServlet;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.LoginConfig;
import org.apache.tomcat.util.descriptor.web.SecurityCollection;
import org.apache.tomcat.util.descriptor.web.SecurityConstraint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the WebDAV target against Tomcat's WebdavServlet with HTTP basic authentication. */
class WebDavIntegrationTest {
	@TempDir
	Path dir;
	Tomcat tomcat;
	Path docBase;
	String url;

	@BeforeEach
	void start() throws Exception {
		docBase = Files.createDirectories(dir.resolve("dav"));
		tomcat = new Tomcat();
		tomcat.setBaseDir(dir.resolve("tomcat").toString());
		tomcat.setPort(0);
		tomcat.getConnector();
		tomcat.addUser("dav", "pa55");
		tomcat.addRole("dav", "writer");
		Context ctx = tomcat.addContext("/files", docBase.toString());
		Wrapper w = Tomcat.addServlet(ctx, "webdav", new WebdavServlet());
		w.addInitParameter("readonly", "false");
		w.addInitParameter("listings", "true");
		ctx.addServletMappingDecoded("/*", "webdav");
		SecurityConstraint sc = new SecurityConstraint();
		sc.addAuthRole("writer");
		SecurityCollection col = new SecurityCollection();
		col.addPattern("/*");
		sc.addCollection(col);
		ctx.addConstraint(sc);
		LoginConfig lc = new LoginConfig();
		lc.setAuthMethod("BASIC");
		lc.setRealmName("dav");
		ctx.setLoginConfig(lc);
		ctx.getPipeline().addValve(new BasicAuthenticator());
		tomcat.start();
		url = "http://127.0.0.1:" + tomcat.getConnector().getLocalPort() + "/files/backups/";
		Files.createDirectories(docBase.resolve("backups"));
	}

	@AfterEach
	void stop() throws Exception {
		tomcat.stop();
		tomcat.destroy();
	}

	@Test
	void putCreatesCollectionsAndDeletes() throws Exception {
		WebDavTarget t = new WebDavTarget(url, "dav", "pa55");
		Path f = dir.resolve("f.bin");
		Files.writeString(f, "dav payload");
		assertFalse(t.exists("x/y/z.bin"));
		t.upload("x/y/z.bin", f); // needs MKCOL for x/ and x/y/ first (Tomcat answers 409 otherwise)
		assertTrue(t.exists("x/y/z.bin"));
		assertArrayEquals("dav payload".getBytes(), Files.readAllBytes(docBase.resolve("backups/x/y/z.bin")));
		t.upload("x/y/z.bin", f); // overwrite
		t.delete("x/y/z.bin");
		assertFalse(t.exists("x/y/z.bin"));
		t.delete("x/y/z.bin");
	}

	@Test
	void missingBaseFolderIsCreated() throws Exception {
		WebDavTarget t = new WebDavTarget(url.replace("/backups/", "/fresh-folder/"), "dav", "pa55");
		Path f = dir.resolve("f.bin");
		Files.writeString(f, "x");
		t.upload("a/b.bin", f);
		assertTrue(Files.isRegularFile(docBase.resolve("fresh-folder/a/b.bin")));
	}

	@Test
	void badCredentialsAreReported() throws Exception {
		WebDavTarget t = new WebDavTarget(url, "dav", "wrong");
		Path f = dir.resolve("f.bin");
		Files.writeString(f, "x");
		IOException e = assertThrows(IOException.class, () -> t.upload("a/b.bin", f));
		assertTrue(e.getMessage().contains("401"), e.getMessage());
	}

	@Test
	void offsiteSyncOverWebDav() throws Exception {
		try (Fixture fx = new Fixture(dir.resolve("fx"))) {
			BackupMeta a = fx.backup("a");
			fx.sync.enqueueUpload(a.id);
			fx.sync.process(new WebDavTarget(url, "dav", "pa55"), fx.workers, true, new Progress(), CancelToken.NONE);
			Set<String> remote = new TreeSet<>();
			Path base = docBase.resolve("backups");
			try (Stream<Path> s = Files.walk(base)) {
				s.filter(Files::isRegularFile).forEach(p -> remote.add(base.relativize(p).toString().replace('\\', '/')));
			}
			assertEquals(new TreeSet<>(fx.expectedKeys(a)), remote);
		}
	}
}
