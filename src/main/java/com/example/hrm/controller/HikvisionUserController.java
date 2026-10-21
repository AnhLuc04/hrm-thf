package com.example.hrm.controller;

        import com.example.hrm.service.HikvisionUserSyncService;
        import lombok.RequiredArgsConstructor;
        import org.springframework.web.bind.annotation.GetMapping;
        import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class HikvisionUserController {

    private final HikvisionUserSyncService service;

    @GetMapping("/api/hikvision/users/sync")
    public String syncUsers() {

        service.syncUsers();

        return "SYNC USER OK";
    }
}