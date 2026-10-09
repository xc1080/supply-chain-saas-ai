package com.simlect.service;

import com.simlect.utils.StringTools;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class PasswordService {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);

    public String encode(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    public boolean matches(String rawPassword, String stored) {
        if (StringTools.isEmpty(rawPassword) || StringTools.isEmpty(stored)) {
            return false;
        }
        if (isBcrypt(stored)) {
            return encoder.matches(rawPassword, stored);
        }
        return StringTools.encodeByMD5(rawPassword).equalsIgnoreCase(stored);
    }

    public boolean isBcrypt(String stored) {
        return stored != null && stored.startsWith("$2");
    }
}
