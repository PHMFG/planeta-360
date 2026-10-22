package br.com.rbs.portal.config;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final JwtEncoder encoder;
    private final long minutos;

    public JwtService(JwtEncoder encoder, @Value("${portal.jwt.expira-minutos}") long minutos) {
        this.encoder = encoder;
        this.minutos = minutos;
    }

    public long expiraEmMinutos() {
        return minutos;
    }

    public String emitir(long usuarioId, String perfil, String email) {
        Instant agora = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("portal-patrocinador")
                .issuedAt(agora)
                .expiresAt(agora.plus(minutos, ChronoUnit.MINUTES))
                .subject(String.valueOf(usuarioId))
                .claim("perfil", perfil)
                .claim("email", email)
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
