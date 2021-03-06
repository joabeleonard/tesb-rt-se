package org.talend.esb.sam.service.security;

public enum EsbSecurityConstants {
    NO("NO"), BASIC("BASIC"), SAML("SAML");

    String esbSecurity;

    private EsbSecurityConstants(String esbSecurity) {
        this.esbSecurity = esbSecurity;
    }

    public static EsbSecurityConstants fromString(String value) {
        if (null == value) {
            return NO;
        }
        for (EsbSecurityConstants esbSecurity : EsbSecurityConstants.values()) {
            if (esbSecurity.esbSecurity.equals(value)) {
                return esbSecurity;
            }
        }
        throw new IllegalArgumentException("Unsupported security value: "
                + value);
    }
}
