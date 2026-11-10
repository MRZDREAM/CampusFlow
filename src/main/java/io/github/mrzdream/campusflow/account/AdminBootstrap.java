package io.github.mrzdream.campusflow.account;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
class AdminBootstrap implements ApplicationRunner {
    private final AccountService accounts;
    private final String username;
    private final String password;

    AdminBootstrap(AccountService accounts, @Value("${campus.auth.admin-username:}") String username,
            @Value("${campus.auth.admin-password:}") String password) {
        this.accounts = accounts;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!username.isBlank() || !password.isEmpty()) {
            accounts.ensureAdmin(username, password);
        }
    }
}
