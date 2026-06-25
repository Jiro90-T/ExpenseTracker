package io.github.jiro.expensetracker.data.local

import io.github.jiro.expensetracker.data.repository.AccountRepository
import io.github.jiro.expensetracker.preferences.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Post-migration reconciliation. The v5→v6 migration seeds the default
 * "Cash wallet" with `currencyCode='USD'` as a placeholder (migrations don't
 * have access to SettingsRepository). On first DB open after migration, this
 * seeder runs and overwrites the placeholder with the user's actual
 * home currency.
 *
 * Idempotent: a no-op once the seeded default matches the home currency.
 */
@Singleton
class AccountSeeder @Inject constructor(
    private val accountRepository: AccountRepository,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun syncDefaultCurrency() {
        val home = settingsRepository.homeCurrency.value
        val default = accountRepository.findDefault() ?: return
        if (default.currencyCode != home) {
            accountRepository.syncDefaultCurrency(home)
        }
    }
}
