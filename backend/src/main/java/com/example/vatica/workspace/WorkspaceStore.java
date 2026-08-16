package com.example.vatica.workspace;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

import com.example.vatica.auth.RequestIdentity;

public interface WorkspaceStore {
    record Entry(String path, long size, boolean directory, String modifiedAt) { }

    Path root(RequestIdentity identity);
    List<Entry> list(RequestIdentity identity, String relativePath) throws IOException;
    Path write(RequestIdentity identity, String relativePath, InputStream content) throws IOException;
    Path read(RequestIdentity identity, String relativePath) throws IOException;
    void delete(RequestIdentity identity, String relativePath) throws IOException;
}
