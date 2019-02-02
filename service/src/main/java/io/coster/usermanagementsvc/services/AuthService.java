package io.coster.usermanagementsvc.services;

import io.coster.usermanagementsvc.contract.LoginRequest;
import io.coster.usermanagementsvc.contract.RegistrationRequest;
import io.coster.usermanagementsvc.domain.AuthToken;
import io.coster.usermanagementsvc.domain.User;
import io.coster.usermanagementsvc.repositories.TokenRepository;
import io.coster.usermanagementsvc.repositories.UserRepository;
import io.coster.usermanagementsvc.services.exceptions.InvalidCredentials;
import io.coster.usermanagementsvc.services.exceptions.UserAlreadyExists;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final long TOKEN_TTL_HOURS = 24L;

    private final TokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public boolean validate(String userId, String token) {
        Optional<AuthToken> optToken = tokenRepository.findByUserIdAndAuthToken(userId, token);
        if (!optToken.isPresent()) {
            return false;
        }
        AuthToken foundToken = optToken.get();
        return foundToken.getExpiry().isAfter(LocalDateTime.now());
    }

    public String register(RegistrationRequest request) {

        // check if user already exists
        Optional<User> byId = userRepository.findById(request.getEmailAddr());
        if (byId.isPresent()) {
            throw new UserAlreadyExists("User is already registered: " + request.getEmailAddr());
        }

        // save new user
        LocalDateTime now = LocalDateTime.now();
        User user = User.builder()
                .emailAddr(request.getEmailAddr())
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .password(passwordEncoder.encode(request.getPassword()))
                .registered(now)
                .lastActive(now).build();
        userRepository.saveAndFlush(user);

        // generate token for new user
        String token = UUID.randomUUID().toString();
        AuthToken tokenEntry = AuthToken.builder()
                .authToken(token)
                .userId(request.getEmailAddr())
                .issued(now)
                .expiry(now.plus(TOKEN_TTL_HOURS, ChronoUnit.HOURS))
                .build();
        tokenRepository.saveAndFlush(tokenEntry);

        return token;
    }


    public String login(LoginRequest request) {

        // check if credentials are valid
        User user = userRepository.findById(request.getEmailAddr())
                .orElseThrow(() -> new InvalidCredentials("Email address is not registered."));

        // check password
        if (!passwordMatch(request, user)) {
            throw new InvalidCredentials("Password provided is incorrect.");
        }

        // delete any previous tokens
        Optional<AuthToken> previousToken = tokenRepository.findById(user.getEmailAddr());
        previousToken.ifPresent(tokenRepository::delete);

        // generate new token
        LocalDateTime now = LocalDateTime.now();
        AuthToken token = AuthToken.builder()
                .authToken(UUID.randomUUID().toString())
                .userId(user.getEmailAddr())
                .issued(now)
                .expiry(now.plus(TOKEN_TTL_HOURS, ChronoUnit.HOURS)).build();
        tokenRepository.saveAndFlush(token);

        // set last_active to now
        user.setLastActive(now);
        userRepository.saveAndFlush(user);

        return token.getAuthToken();
    }

    private boolean passwordMatch(LoginRequest request, User user) {
        return passwordEncoder.matches(request.getPassword(), user.getPassword());
    }
}