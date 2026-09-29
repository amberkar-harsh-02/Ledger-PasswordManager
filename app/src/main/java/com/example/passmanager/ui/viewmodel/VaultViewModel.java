package com.example.passmanager.ui.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;

import com.example.passmanager.data.model.Credential;
import com.example.passmanager.repository.CredentialRepository;

import java.util.List;

public class VaultViewModel extends AndroidViewModel {

    // Keep a reference to the repository
    private final CredentialRepository repository;

    // LiveData caches the list of passwords. If the database updates, this updates automatically.
    private final LiveData<List<Credential>> allCredentials;

    public VaultViewModel(@NonNull Application application) {
        super(application);
        repository = new CredentialRepository(application);
        allCredentials = repository.getAllCredentials();
    }

    // The UI will call this method to observe the password list
    public LiveData<List<Credential>> getAllCredentials() {
        return allCredentials;
    }

    // Wrapper method for inserting a new password
    public void insert(Credential credential) {
        repository.insert(credential);
    }

    // Wrapper method for deleting a password
    public void delete(Credential credential) {
        repository.delete(credential);
    }

    public void update(Credential credential) {
        repository.update(credential);
    }

    public void upgradeLegacyTotpSecrets() {
        repository.upgradeLegacyTotpSecrets();
    }
}