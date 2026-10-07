package com.medicity.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Email addresses")
class EmailAddressesTest {

    @Test
    @DisplayName("normalising trims and lower-cases, and leaves null alone")
    void normalise() {
        assertThat(EmailAddresses.normalise("  Meera.Nair@Example.COM ")).isEqualTo("meera.nair@example.com");
        assertThat(EmailAddresses.normalise(null)).isNull();
    }

    @Test
    @DisplayName("masking keeps the first letter and the whole domain")
    void masked() {
        assertThat(EmailAddresses.masked("meera@gmail.com")).isEqualTo("m•••@gmail.com");
        assertThat(EmailAddresses.masked("a@x.in")).isEqualTo("a•••@x.in");
        assertThat(EmailAddresses.masked("first.last+tag@sub.example.co.in")).isEqualTo("f•••@sub.example.co.in");
    }

    @Test
    @DisplayName("something without a usable local part is fully hidden rather than echoed")
    void maskedMalformed() {
        assertThat(EmailAddresses.masked("@nolocal.com")).isEqualTo("•••");
        assertThat(EmailAddresses.masked("noatsign")).isEqualTo("•••");
    }
}
