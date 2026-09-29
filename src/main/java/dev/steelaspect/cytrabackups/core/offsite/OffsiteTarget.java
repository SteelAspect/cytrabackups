package dev.steelaspect.cytrabackups.core.offsite;

import java.io.IOException;
import java.nio.file.Path;

/** A remote location mirroring the repository layout (keys like "blobs/ab/cd/<hash>", "backups/000001/meta.json"). */
public interface OffsiteTarget extends AutoCloseable {
	void upload(String key, Path file) throws IOException;

	boolean exists(String key) throws IOException;

	void delete(String key) throws IOException;

	String describe();

	/** Stable identity of the destination (everything that decides where objects end up). */
	default String id() {
		return describe();
	}

	@Override
	default void close() throws IOException {
	}
}
