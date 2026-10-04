package com.medicity.chemist;

import com.medicity.user.Role;
import com.medicity.user.User;
import com.medicity.user.UserRepository;

import java.time.LocalTime;

/** Shared fixtures for the pharmacy integration tests. */
final class ChemistTestSupport {

    /** Indiranagar, Bengaluru. */
    static final double CENTRE_LAT = 12.9784;
    static final double CENTRE_LNG = 77.6408;

    private static final double KM_PER_DEGREE = 111.195;

    private ChemistTestSupport() {
    }

    static double northOf(double lat, double km) {
        return lat + km / KM_PER_DEGREE;
    }

    static double eastOf(double lat, double lng, double km) {
        return lng + km / (KM_PER_DEGREE * Math.cos(Math.toRadians(lat)));
    }

    static User persistUser(UserRepository users, String email, String name, Role role) {
        User user = User.builder()
                .passwordHash("{noop}irrelevant")
                .fullName(name)
                .role(role)
                .enabled(true)
                .build();
        user.setEmail(email);
        return users.save(user);
    }

    static Pharmacy persistShop(UserRepository users, PharmacyRepository shops, String slug,
                                double lat, double lng, Pharmacy.Status status) {
        User owner = persistUser(users, slug + "@medicity.test", "Owner of " + slug, Role.PHARMACIST);
        return shops.save(Pharmacy.builder()
                .user(owner)
                .name(slug)
                .drugLicenceNumber("KA-" + slug.toUpperCase())
                .phone("+919876500000")
                .addressLine("1 " + slug + " Road")
                .city("Bengaluru")
                .latitude(lat)
                .longitude(lng)
                .opensAt(LocalTime.of(0, 0))
                .closesAt(LocalTime.of(23, 59))
                .status(status)
                .build());
    }
}
