package com.devlink.server.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class SignupPasswordValidator implements ConstraintValidator<SignupPassword, String> {
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        if (value.length() < 8 || value.length() > 32) {
            return false;
        }

        boolean hasUppercase = false;
        boolean hasLowercase = false;
        boolean hasDigit = false;
        boolean hasPunctuation = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character >= 'A' && character <= 'Z') {
                hasUppercase = true;
            } else if (character >= 'a' && character <= 'z') {
                hasLowercase = true;
            } else if (character >= '0' && character <= '9') {
                hasDigit = true;
            } else if (character >= '!' && character <= '~') {
                hasPunctuation = true;
            } else {
                return false;
            }
        }

        int characterTypes = (hasUppercase ? 1 : 0) + (hasLowercase ? 1 : 0)
                + (hasDigit ? 1 : 0) + (hasPunctuation ? 1 : 0);
        return characterTypes >= 3;
    }
}
