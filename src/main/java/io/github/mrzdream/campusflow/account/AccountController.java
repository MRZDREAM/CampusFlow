package io.github.mrzdream.campusflow.account;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AccountController {
    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountService.Account register(@Valid @RequestBody Credentials input) {
        return accounts.register(input.username(), input.password());
    }

    @PostMapping("/login")
    public TokenService.AccessToken login(@Valid @RequestBody Credentials input) {
        return accounts.login(input.username(), input.password());
    }

    public record Credentials(
            @NotNull @Pattern(regexp = "[A-Za-z0-9._-]{3,40}") String username,
            @NotNull @Size(min = 12, max = 72) String password) { }
}
