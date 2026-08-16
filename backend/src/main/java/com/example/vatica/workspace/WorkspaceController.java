package com.example.vatica.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;

/** 迭代 14：云工作区文件列表、上传、下载与删除。 */
@RestController
@RequestMapping("/api/workspace/files")
public class WorkspaceController {

    private final WorkspaceStore store;

    public WorkspaceController(WorkspaceStore store) {
        this.store = store;
    }

    @GetMapping
    public List<WorkspaceStore.Entry> list(@RequestParam(defaultValue = "") String path) throws IOException {
        return store.list(RequestIdentityContext.require(), path);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public WorkspaceStore.Entry upload(@RequestPart MultipartFile file,
            @RequestParam(defaultValue = "") String directory) throws IOException {
        if (file == null || file.isEmpty() || file.getOriginalFilename() == null) {
            throw new IllegalArgumentException("操作失败：请选择要上传的文件。");
        }
        String filename = Path.of(file.getOriginalFilename()).getFileName().toString();
        String relative = directory == null || directory.isBlank() ? filename : directory + "/" + filename;
        RequestIdentity identity = RequestIdentityContext.require();
        Path written = store.write(identity, relative, file.getInputStream());
        Path root = store.root(identity);
        return new WorkspaceStore.Entry(root.relativize(written).toString().replace('\\', '/'),
                Files.size(written), false, Files.getLastModifiedTime(written).toInstant().toString());
    }

    @GetMapping("/content")
    public ResponseEntity<InputStreamResource> download(@RequestParam String path) throws IOException {
        Path file = store.read(RequestIdentityContext.require(), path);
        String contentType = Files.probeContentType(file);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment().filename(file.getFileName().toString()).build());
        return ResponseEntity.ok().headers(headers)
                .contentType(contentType == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(contentType))
                .contentLength(Files.size(file)).body(new InputStreamResource(Files.newInputStream(file)));
    }

    @DeleteMapping
    public void delete(@RequestParam String path) throws IOException {
        store.delete(RequestIdentityContext.require(), path);
    }
}
