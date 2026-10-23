package br.com.rbs.portal.service;

public record UsuarioLogado(long id, String perfil) {
    public boolean rbs() {
        return "ADMIN_RBS".equals(perfil);
    }
}
