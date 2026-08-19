package com.example.vatica.knowledge;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 迭代 19B：知识库生命周期和人工检索 API。 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeBaseService service;

    public KnowledgeController(KnowledgeBaseService service) {
        this.service = service;
    }

    public record ImportRequest(String path, KnowledgeVisibility visibility) {
    }

    public record SearchRequest(String query, Integer topK) {
    }

    @PostMapping("/documents")
    public KnowledgeBaseService.DocumentView importDocument(@RequestBody ImportRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("操作失败：知识库导入请求不能为空。");
        }
        return service.importDocument(request.path(), request.visibility());
    }

    @GetMapping("/documents")
    public List<KnowledgeBaseService.DocumentView> listDocuments() {
        return service.listDocuments();
    }

    @DeleteMapping("/documents/{id}")
    public void deleteDocument(@PathVariable long id) {
        service.deleteDocument(id);
    }

    @PostMapping("/search")
    public KnowledgeBaseService.SearchResult search(@RequestBody SearchRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("操作失败：知识库检索请求不能为空。");
        }
        return service.search(request.query(), request.topK() == null ? 5 : request.topK());
    }
}
