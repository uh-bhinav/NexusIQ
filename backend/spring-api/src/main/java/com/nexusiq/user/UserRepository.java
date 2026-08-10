package com.nexusiq.user;

import com.nexusiq.user.entity.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    // email column is citext, so this comparison is case-insensitive at the DB level.
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
