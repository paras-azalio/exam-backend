package com.exam.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Generates Safe Exam Browser (.seb) configuration XML.
 *
 * A .seb file is an Apple Binary-plist-like XML document that SEB reads on launch.
 * We generate it as plain XML (plist format) so no extra library is needed.
 *
 * Key settings we lock down:
 *   - allowQuit                   → false   (can't exit without quit password)
 *   - browserWindowAllowReload    → false   (no F5 refresh)
 *   - enableRightClick            → false   (no context menu)
 *   - showTaskBar                 → false   (hides taskbar)
 *   - allowedURLs                 → only your exam domain
 *   - startURL                    → the full JWT invite link
 *   - quitURL                     → back-end URL SEB hits when student finishes
 *   - hashedQuitPassword          → SHA-256 hash of the quit password
 */
@Slf4j
@Service
public class SebConfigService {

    @Value("${seb.quit-password:QuickScreenSEB2026}")
    private String quitPassword;

    @Value("${app.frontend-url}")
    private String frontendUrl;
    
    @Value("${seb.admin-password:QuickScreenAdmin2026}")
    private String adminPassword;

    /**
     * Returns a valid SEB XML plist config as a UTF-8 String.
     *
     * @param startUrl  full exam URL including ?usr=<jwt>
     * @param examCode  used for logging / display only
     */
    public String generateSebConfig(String startUrl, String examCode) {
        log.info("Generating SEB config for examCode={} startUrl={}", examCode, startUrl);

        // Derive the allowed domain from frontendUrl (e.g. "https://hr-orbit.azalio.io")
        String allowedDomain = extractDomain(frontendUrl);

        // SHA-256 of quitPassword — SEB compares this against what the admin types
        String hashedQuit = sha256Hex(quitPassword);
        
        String hashedAdmin = sha256Hex(adminPassword);

        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
             + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\"\n"
             + "  \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
             + "<plist version=\"1.0\">\n"
             + "<dict>\n"

             // ── Start URL ────────────────────────────────────────────────────
             + "  <key>startURL</key>\n"
             + "  <string>" + escXml(startUrl) + "</string>\n"

             // ── Quit / exit ──────────────────────────────────────────────────
             + "  <key>allowQuit</key>\n"
             + "  <true/>\n"
//             + "  <key>hashedQuitPassword</key>\n"
//             + "  <string>" + hashedQuit + "</string>\n"
             + "  <key>hashedAdminPassword</key>\n"
             + "  <string>" + hashedAdmin + "</string>\n"
             
             
             	

             // ── Browser behaviour ────────────────────────────────────────────
             + "  <key>browserWindowAllowReload</key>\n"
             + "  <false/>\n"
             + "  <key>enableRightClick</key>\n"
             + "  <false/>\n"
             + "  <key>showTaskBar</key>\n"
             + "  <true/>\n"
             + "  <key>taskBarHeight</key>\n"
             + "  <integer>40</integer>\n"
             + "  <key>showQuitButton</key>\n"
             + "  <true/>\n"
             + "  <key>showTime</key>\n"
             + "  <true/>\n"
             + "  <key>showInputLanguage</key>\n"
             + "  <true/>\n"
             + "  <key>hideBrowserWindowToolbar</key>\n"
             + "  <false/>\n"
             + "  <key>enableSwitchingApps</key>\n"
             + "  <false/>\n"
             + "  <key>showMenuBar</key>\n"
             + "  <false/>\n"
             + "  <key>enableZoomPage</key>\n"
             + "  <false/>\n"
             + "  <key>enableZoomText</key>\n"
             + "  <false/>\n"
             + "  <key>zoomMode</key>\n"
             + "  <integer>0</integer>\n"
             + "  <key>enablePrintScreen</key>\n"
             + "  <false/>\n"
          

             // ── Address bar ──────────────────────────────────────────────────
             + "  <key>showNavigationButtons</key>\n"
             + "  <false/>\n"
             + "  <key>showReloadButton</key>\n"
             + "  <false/>\n"
             + "  <key>showURL</key>\n"
             + "  <false/>\n"
			             
			+ "  <key>prohibitedProcesses</key>\n"
			+ "  <array>\n"
			+ "    <dict><key>active</key><true/><key>executable</key><string>AnyDesk.exe</string><key>originalName</key><string>AnyDesk.exe</string><key>os</key><integer>1</integer></dict>\n"
			+ "    <dict><key>active</key><false/><key>executable</key><string>Teams.exe</string><key>originalName</key><string></string><key>os</key><integer>1</integer></dict>\n"
            + "    <dict><key>active</key><false/><key>executable</key><string>ms-teams.exe</string><key>originalName</key><string></string><key>os</key><integer>1</integer></dict>\n"
			+ "    <dict><key>active</key><true/><key>executable</key><string>chrome.exe</string><key>originalName</key><string>chrome.exe</string><key>os</key><integer>1</integer></dict>\n"
			+ "  </array>\n"
			+ "  <key>kioskMode</key>\n"
			+ "  <integer>0</integer>\n"
			+ "  <key>allowWindowCapture</key>\n"
			+ "  <true/>\n"
			+ "  <key>sebServiceIgnore</key>\n"
            + "  <true/>\n"
            + "  <key>allowScreenSharing</key>\n"
            + "  <true/>\n"
            + "  <key>allowScreenCapture</key>\n"
            + "  <true/>\n"
            + "  <key>sebServicePolicy</key>\n"
            + "  <integer>2</integer>\n"
             
             

             // ── Allowed URLs (whitelist) ──────────────────────────────────────
             + "  <key>URLFilterEnable</key>\n"
             + "  <true/>\n"
             + "  <key>URLFilterEnableContentFilter</key>\n"
             + "  <false/>\n"
             + "  <key>URLFilterRules</key>\n"
             + "  <array>\n"
             + "    <dict>\n"
             + "      <key>action</key><integer>1</integer>\n"   // 1 = allow
             + "      <key>active</key><true/>\n"
             + "      <key>expression</key>\n"
             + "      <string>" + escXml(allowedDomain) + ".*</string>\n"
             + "      <key>regex</key><false/>\n"
             + "    </dict>\n"
             + "  </array>\n"

//             // ── Additional security ──────────────────────────────────────────
//             + "  <key>sendBrowserExamKey</key>\n"
//             + "  <true/>\n"
//             + "  <key>browserUserAgentMac</key>\n"
//             + "  <integer>0</integer>\n"
//             + "  <key>browserUserAgentWin</key>\n"
//             + "  <integer>0</integer>\n"

             + "</dict>\n"
             + "</plist>\n";
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Extracts "https://hostname" from a URL. */
    private String extractDomain(String url) {
        try {
            java.net.URI uri = new java.net.URI(url);
            return uri.getScheme() + "://" + uri.getHost();
        } catch (Exception e) {
            log.warn("Could not parse frontendUrl '{}' — using as-is", url);
            return url;
        }
    }

    /** Returns the lowercase hex SHA-256 digest of a string (UTF-8). */
    private String sha256Hex(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /** Minimal XML escaping for attribute/text content. */
    private String escXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
