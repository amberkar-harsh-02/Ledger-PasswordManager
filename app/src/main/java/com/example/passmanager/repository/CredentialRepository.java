package com.example.passmanager.repository;

import android.app.Application;
import androidx.lifecycle.LiveData;
import com.example.passmanager.data.local.CredentialDao;
import com.example.passmanager.data.local.VaultDatabase;
import com.example.passmanager.data.model.Credential;
import com.example.passmanager.security.TotpSecretCodec;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CredentialRepository {
    private CredentialDao credentialDao;
    private LiveData<List<Credential>> allCredentials;
    private ExecutorService executorService;

    public CredentialRepository(Application application) {
        VaultDatabase db = VaultDatabase.getDatabase(application);
        credentialDao = db.credentialDao();
        allCredentials = credentialDao.getAllCredentials();
        executorService = Executors.newSingleThreadExecutor();
    }

    public LiveData<List<Credential>> getAllCredentials() {
        return allCredentials;
    }

    public void insert(Credential credential) {
        executorService.execute(() -> credentialDao.insertCredential(credential));
    }

    public void delete(Credential credential) {
        executorService.execute(() -> credentialDao.deleteCredential(credential));
    }

    public void update(Credential credential) {
        executorService.execute(() -> credentialDao.updateCredential(credential));
    }

    public java.util.List<com.example.passmanager.data.model.Credential> getAllCredentialsSync() {
        return credentialDao.getAllCredentialsSync();
    }

    // Call off the main thread
    public Credential getCredentialByIdSync(int id) {
        return credentialDao.getCredentialByIdSync(id);
    }

    // One-time upgrade: encrypt any 2FA secrets still stored as plaintext. Safe to call repeatedly.
    public void upgradeLegacyTotpSecrets() {
        executorService.execute(() -> {
            for (Credential credential : credentialDao.getAllCredentialsSync()) {
                String stored = credential.getTotpSecret();
                if (stored == null || stored.trim().isEmpty() || TotpSecretCodec.isSealed(stored)) continue;
                try {
                    credential.setTotpSecret(TotpSecretCodec.seal(stored));
                    credentialDao.updateCredential(credential);
                } catch (Exception e) {
                    android.util.Log.e("CredentialRepository", "Could not encrypt a 2FA secret", e);
                }
            }
        });
    }
}