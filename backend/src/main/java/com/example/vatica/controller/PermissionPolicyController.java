package com.example.vatica.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.vatica.permission.FilePermissionPolicy;
import com.example.vatica.permission.PermissionPolicyService;

@RestController
@RequestMapping("/api/permissions/policy")
public class PermissionPolicyController {
    private final PermissionPolicyService service;
    public PermissionPolicyController(PermissionPolicyService service) { this.service = service; }
    @GetMapping public FilePermissionPolicy get() { return service.current(); }
    @PutMapping public FilePermissionPolicy save(@RequestBody FilePermissionPolicy policy) { return service.save(policy); }
}
