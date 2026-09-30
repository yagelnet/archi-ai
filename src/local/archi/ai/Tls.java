package local.archi.ai;

import java.net.Socket;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * TLS context that trusts both the JRE's CA list and the Windows certificate store.
 * Antivirus HTTPS scanning (e.g. Kaspersky) and corporate proxies re-sign traffic with a root
 * that exists only in Windows; with the JRE list alone Java fails with "PKIX path building failed".
 */
final class Tls {

    private static SSLContext context;

    private Tls() {}

    static synchronized SSLContext context() {
        if (context != null) return context;
        try {
            List<X509TrustManager> tms = new ArrayList<>();
            tms.add(trustManager(null));
            try {
                KeyStore win = KeyStore.getInstance("Windows-ROOT");
                win.load(null, null);
                tms.add(trustManager(win));
            } catch (Exception ignored) {
                // not on Windows: the JRE list is enough
            }
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] {new AnyOf(tms)}, null);
            context = ctx;
        } catch (Exception e) {
            try {
                context = SSLContext.getDefault();
            } catch (Exception e2) {
                throw new IllegalStateException(e2);
            }
        }
        return context;
    }

    private static X509TrustManager trustManager(KeyStore ks) throws Exception {
        TrustManagerFactory f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        f.init(ks);
        for (TrustManager tm : f.getTrustManagers()) {
            if (tm instanceof X509TrustManager x) return x;
        }
        throw new IllegalStateException("no X509TrustManager");
    }

    /** Accepts a server chain if any of the delegates accepts it. */
    private static final class AnyOf extends X509ExtendedTrustManager {
        private final List<X509TrustManager> tms;

        AnyOf(List<X509TrustManager> tms) {
            this.tms = tms;
        }

        private interface Check {
            void run(X509TrustManager tm) throws CertificateException;
        }

        private void any(Check c) throws CertificateException {
            CertificateException first = null;
            for (X509TrustManager tm : tms) {
                try {
                    c.run(tm);
                    return;
                } catch (CertificateException e) {
                    if (first == null) first = e;
                }
            }
            throw first != null ? first : new CertificateException("no trust managers");
        }

        @Override public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
            any(tm -> tm.checkServerTrusted(chain, auth));
        }

        @Override public void checkServerTrusted(X509Certificate[] chain, String auth, Socket s) throws CertificateException {
            any(tm -> {
                if (tm instanceof X509ExtendedTrustManager x) x.checkServerTrusted(chain, auth, s);
                else tm.checkServerTrusted(chain, auth);
            });
        }

        @Override public void checkServerTrusted(X509Certificate[] chain, String auth, SSLEngine e) throws CertificateException {
            any(tm -> {
                if (tm instanceof X509ExtendedTrustManager x) x.checkServerTrusted(chain, auth, e);
                else tm.checkServerTrusted(chain, auth);
            });
        }

        @Override public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException {
            any(tm -> tm.checkClientTrusted(chain, auth));
        }

        @Override public void checkClientTrusted(X509Certificate[] chain, String auth, Socket s) throws CertificateException {
            checkClientTrusted(chain, auth);
        }

        @Override public void checkClientTrusted(X509Certificate[] chain, String auth, SSLEngine e) throws CertificateException {
            checkClientTrusted(chain, auth);
        }

        @Override public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> all = new ArrayList<>();
            for (X509TrustManager tm : tms) all.addAll(List.of(tm.getAcceptedIssuers()));
            return all.toArray(new X509Certificate[0]);
        }
    }
}
