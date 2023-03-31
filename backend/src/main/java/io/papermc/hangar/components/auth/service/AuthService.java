package io.papermc.hangar.components.auth.service;

import io.papermc.hangar.HangarComponent;
import io.papermc.hangar.components.auth.dao.UserCredentialDAO;
import io.papermc.hangar.components.auth.dao.VerificationCodeDao;
import io.papermc.hangar.components.auth.model.credential.Credential;
import io.papermc.hangar.components.auth.model.credential.CredentialType;
import io.papermc.hangar.components.auth.model.credential.PasswordCredential;
import io.papermc.hangar.components.auth.model.db.UserCredentialTable;
import io.papermc.hangar.components.auth.model.db.VerificationCodeTable;
import io.papermc.hangar.db.customtypes.JSONB;
import io.papermc.hangar.db.dao.internal.table.UserDAO;
import io.papermc.hangar.exceptions.HangarApiException;
import io.papermc.hangar.exceptions.WebHookException;
import io.papermc.hangar.model.api.UserNameChange;
import io.papermc.hangar.model.common.Permission;
import io.papermc.hangar.model.db.UserTable;
import io.papermc.hangar.components.auth.model.dto.SignupForm;
import io.papermc.hangar.security.authentication.HangarPrincipal;
import io.papermc.hangar.service.ValidationService;
import io.papermc.hangar.service.internal.MailService;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService extends HangarComponent implements UserDetailsService {

    private final UserDAO userDAO;
    private final UserCredentialDAO userCredentialDAO;
    private final PasswordEncoder passwordEncoder;
    private final ValidationService validationService;
    private final MailService mailService;
    private final VerificationCodeDao verificationCodeDao;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(final UserDAO userDAO, final UserCredentialDAO userCredentialDAO, final PasswordEncoder passwordEncoder, final ValidationService validationService, final MailService mailService, final VerificationCodeDao verificationCodeDao) {
        this.userDAO = userDAO;
        this.userCredentialDAO = userCredentialDAO;
        this.passwordEncoder = passwordEncoder;
        this.validationService = validationService;
        this.mailService = mailService;
        this.verificationCodeDao = verificationCodeDao;
    }

    @Transactional
    public UserTable registerUser(final SignupForm form) {
        if (!this.validationService.isValidUsername(form.username())) {
            throw new HangarApiException("nav.user.error.invalidUsername");
        }
        if (!this.validPassword(form.password())) {
            throw new HangarApiException("dum");
        }
        // TODO check if user exists and shit
        final UserTable userTable = this.userDAO.create(UUID.randomUUID(), form.username(), form.email(), null, "en", List.of(), false, "light");

        this.registerCredential(userTable.getUserId(), new PasswordCredential(this.passwordEncoder.encode(form.password())));

        return userTable;
    }

    public boolean validPassword(final String password) {
        return true; // TODO valid passowrd
    }

    public void registerCredential(final long userId, final Credential credential) {
        this.userCredentialDAO.insert(userId, new JSONB(credential), credential.type());
    }

    public void removeCredential(final long userId, final CredentialType type) {
        this.userCredentialDAO.remove(userId, type);
    }

    public void updateCredential(final long userId, final Credential credential) {
        this.userCredentialDAO.update(userId, new JSONB(credential), credential.type());
    }

    public @Nullable UserCredentialTable getCredential(final long userId, final CredentialType type) {
        return this.userCredentialDAO.getByType(type, userId);
    }

    @Override
    public HangarPrincipal loadUserByUsername(final String username) throws UsernameNotFoundException {
        if (username == null) {
            throw new UsernameNotFoundException("no user with null username");
        }
        System.out.println("loading user " + username);
        final UserTable userTable = this.userDAO.getUserTable(username);
        if (userTable == null) {
            throw new UsernameNotFoundException("no user in table");
        }
        final UserCredentialTable passwordCredential = this.userCredentialDAO.getByType(CredentialType.PASSWORD, userTable.getUserId());
        if (passwordCredential == null) {
            throw new UsernameNotFoundException("no password credentials in table");
        }
        final String password = passwordCredential.getCredential().get(PasswordCredential.class).hashedPassword();
        // TODO load proper perms
        return new HangarPrincipal(userTable.getUserId(), userTable.getName(), userTable.isLocked(), Permission.ViewPublicInfo, password);
    }

    private void handleUsernameChange(final UserTable user, final String newName) {
        // make sure a user with that name doesn't exist yet
        if (this.userDAO.getUserTable(newName) != null) {
            throw new HangarApiException("A user with that name already exists!");
        }
        // check that last change was long ago
        final List<UserNameChange> userNameHistory = this.userDAO.getUserNameHistory(user.getUuid());
        if (!userNameHistory.isEmpty()) {
            userNameHistory.sort(Comparator.comparing(UserNameChange::date).reversed());
            final OffsetDateTime nextChange = userNameHistory.get(0).date().plus(this.config.user.nameChangeInterval(), ChronoUnit.DAYS);
            if (nextChange.isAfter(OffsetDateTime.now())) {
                throw WebHookException.of("You can't change your name that soon! You have to wait till " + nextChange.format(DateTimeFormatter.RFC_1123_DATE_TIME));
            }
        }
        // record the change into the db
        this.userDAO.recordNameChange(user.getUuid(), user.getName(), newName);
    }

    public boolean verifyResetCode(final String email, final String code, boolean delete) {
        final UserTable userTable = this.userDAO.getUserTable(email);
        if (userTable == null) {
            return false;
        }

        final VerificationCodeTable table = this.verificationCodeDao.get(VerificationCodeTable.VerificationCodeType.PASSWORD_RESET, userTable.getUserId());
        if (table.getCreatedAt().plus(10, ChronoUnit.MINUTES).isBefore(OffsetDateTime.now())) {
            return false; // TODO expired
        }

        if (!table.getCode().equals(code)) {
            return false;
        }

        if (delete) {
            this.verificationCodeDao.delete(table.getId());
        }

        return true;
    }

    public void sendResetCode(final String email) {
        final UserTable userTable = this.userDAO.getUserTable(email);
        if (userTable == null) {
            return;
        }

        this.verificationCodeDao.deleteOld(VerificationCodeTable.VerificationCodeType.PASSWORD_RESET, userTable.getUserId());

        final String code = String.format("%06d", this.secureRandom.nextInt(999999));
        this.verificationCodeDao.insert(new VerificationCodeTable(userTable.getUserId(), VerificationCodeTable.VerificationCodeType.PASSWORD_RESET, code));

        this.mailService.queueEmail("Hangar Password Reset", userTable.getEmail(), """
            Hi %s,
            you dum dum did forget your password, enter %s to reset.
            if you did not request this email, ignore it.
            """.formatted(userTable.getName(), code));
    }
}
