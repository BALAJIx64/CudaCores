package com.guardiangrid.modules.communication.persistence

import com.guardiangrid.core.models.EmergencyContact
import com.guardiangrid.core.state.EmergencyStateHolder
import java.io.File
import java.util.UUID

/**
 * Durable user-configured preferences and emergency contact settings.
 */
data class UserSettings(
    val contacts: List<EmergencyContact> = emptyList(),
    val isDiscreetModeEnabled: Boolean = false,
    val screamSensitivity: Float = 0.80f,
    val motionSensitivity: Float = 1.0f,
    val countdownDurationSeconds: Int = 10,
    val autoStartProtectionOnBoot: Boolean = false,
    val schemaVersion: Int = 1
)

/**
 * Interface defining persistent settings and contact operations.
 */
interface SettingsRepository {
    fun getSettings(): UserSettings
    fun saveSettings(settings: UserSettings): Boolean
    fun addContact(contact: EmergencyContact): Result<EmergencyContact>
    fun updateContact(contact: EmergencyContact): Result<EmergencyContact>
    fun deleteContact(contactId: String): Boolean
    fun setPrimaryContact(contactId: String): Boolean
    fun getPrimaryContact(): EmergencyContact?
    fun getSecondaryContacts(): List<EmergencyContact>
    fun setDiscreetMode(enabled: Boolean): Boolean
    fun setScreamSensitivity(sensitivity: Float): Boolean
    fun setMotionSensitivity(sensitivity: Float): Boolean = true
    fun setCountdownDuration(seconds: Int): Boolean
    fun syncToStateHolder()
}

/**
 * File-backed persistent settings repository with atomic writes and input validation.
 */
class FileSettingsRepository(
    private val storageDir: File
) : SettingsRepository {

    private val settingsFile: File get() = File(storageDir, "guardian_settings.json")
    private var cachedSettings: UserSettings

    init {
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }
        cachedSettings = loadFromDisk()
    }

    @Synchronized
    override fun getSettings(): UserSettings = cachedSettings

    @Synchronized
    override fun saveSettings(settings: UserSettings): Boolean {
        return try {
            cachedSettings = settings
            flushToDisk(settings)
            syncToStateHolder()
            true
        } catch (e: Exception) {
            false
        }
    }

    @Synchronized
    override fun addContact(contact: EmergencyContact): Result<EmergencyContact> {
        val validation = validateContact(contact)
        if (validation.isFailure) {
            return Result.failure(validation.exceptionOrNull()!!)
        }

        val normPhone = PhoneNumberValidator.normalize(contact.phoneNumber)
        val duplicate = cachedSettings.contacts.any {
            it.id == contact.id || PhoneNumberValidator.normalize(it.phoneNumber) == normPhone
        }
        if (duplicate) {
            return Result.failure(IllegalArgumentException("A contact with this phone number or ID already exists"))
        }

        val assignedId = if (contact.id.isBlank()) UUID.randomUUID().toString() else contact.id
        val makePrimary = contact.isPrimary || cachedSettings.contacts.isEmpty()
        val cleanedContact = contact.copy(
            id = assignedId,
            phoneNumber = normPhone,
            isPrimary = makePrimary
        )

        val updatedContacts = if (makePrimary) {
            cachedSettings.contacts.map { it.copy(isPrimary = false) } + cleanedContact
        } else {
            cachedSettings.contacts + cleanedContact
        }
        saveSettings(cachedSettings.copy(contacts = updatedContacts))
        return Result.success(cleanedContact)
    }

    @Synchronized
    override fun updateContact(contact: EmergencyContact): Result<EmergencyContact> {
        val validation = validateContact(contact)
        if (validation.isFailure) {
            return Result.failure(validation.exceptionOrNull()!!)
        }

        val existingIndex = cachedSettings.contacts.indexOfFirst { it.id == contact.id }
        if (existingIndex == -1) {
            return Result.failure(IllegalArgumentException("Contact not found with ID: ${contact.id}"))
        }

        val normPhone = PhoneNumberValidator.normalize(contact.phoneNumber)
        val phoneConflict = cachedSettings.contacts.any {
            it.id != contact.id && PhoneNumberValidator.normalize(it.phoneNumber) == normPhone
        }
        if (phoneConflict) {
            return Result.failure(IllegalArgumentException("Another contact already uses this phone number"))
        }

        val cleanedContact = contact.copy(phoneNumber = normPhone)
        val updatedContacts = cachedSettings.contacts.toMutableList()
        if (cleanedContact.isPrimary) {
            for (i in updatedContacts.indices) {
                updatedContacts[i] = updatedContacts[i].copy(isPrimary = false)
            }
        }
        updatedContacts[existingIndex] = cleanedContact
        saveSettings(cachedSettings.copy(contacts = updatedContacts))
        return Result.success(cleanedContact)
    }

    @Synchronized
    override fun deleteContact(contactId: String): Boolean {
        val initialSize = cachedSettings.contacts.size
        val contactToDelete = cachedSettings.contacts.find { it.id == contactId }
        val remaining = cachedSettings.contacts.filterNot { it.id == contactId }
        if (remaining.size == initialSize) {
            return false
        }
        val updated = if (contactToDelete?.isPrimary == true && remaining.isNotEmpty()) {
            remaining.mapIndexed { idx, c -> if (idx == 0) c.copy(isPrimary = true) else c }
        } else {
            remaining
        }
        return saveSettings(cachedSettings.copy(contacts = updated))
    }

    @Synchronized
    override fun setPrimaryContact(contactId: String): Boolean {
        val exists = cachedSettings.contacts.any { it.id == contactId }
        if (!exists) return false
        val updated = cachedSettings.contacts.map { c ->
            c.copy(isPrimary = (c.id == contactId))
        }
        return saveSettings(cachedSettings.copy(contacts = updated))
    }

    override fun getPrimaryContact(): EmergencyContact? {
        return cachedSettings.contacts.firstOrNull { it.isPrimary }
            ?: cachedSettings.contacts.firstOrNull()
    }

    override fun getSecondaryContacts(): List<EmergencyContact> {
        val primary = getPrimaryContact()
        return cachedSettings.contacts.filterNot { it.id == primary?.id }
    }

    @Synchronized
    override fun setDiscreetMode(enabled: Boolean): Boolean {
        return saveSettings(cachedSettings.copy(isDiscreetModeEnabled = enabled))
    }

    @Synchronized
    override fun setScreamSensitivity(sensitivity: Float): Boolean {
        val clamped = sensitivity.coerceIn(0.1f, 1.0f)
        return saveSettings(cachedSettings.copy(screamSensitivity = clamped))
    }

    @Synchronized
    override fun setMotionSensitivity(sensitivity: Float): Boolean {
        val clamped = sensitivity.coerceIn(0.5f, 1.5f)
        return saveSettings(cachedSettings.copy(motionSensitivity = clamped))
    }

    @Synchronized
    override fun setCountdownDuration(seconds: Int): Boolean {
        val clamped = seconds.coerceIn(3, 30)
        return saveSettings(cachedSettings.copy(countdownDurationSeconds = clamped))
    }

    override fun syncToStateHolder() {
        EmergencyStateHolder.updateTrustedContacts(cachedSettings.contacts)
        EmergencyStateHolder.setDiscreetModeEnabled(cachedSettings.isDiscreetModeEnabled)
    }

    private fun validateContact(contact: EmergencyContact): Result<Unit> {
        if (contact.name.trim().isBlank()) {
            return Result.failure(IllegalArgumentException("Contact name cannot be blank"))
        }
        val phoneResult = PhoneNumberValidator.validateAndNormalize(contact.phoneNumber)
        if (!phoneResult.isValid) {
            return Result.failure(IllegalArgumentException(phoneResult.errorMessage ?: "Invalid phone number"))
        }
        return Result.success(Unit)
    }

    private fun normalizePhoneNumber(phone: String): String {
        return PhoneNumberValidator.normalize(phone)
    }

    @Synchronized
    private fun flushToDisk(settings: UserSettings) {
        val tempFile = File(storageDir, "guardian_settings.json.tmp")
        val content = serializeSettings(settings)
        tempFile.writeText(content, Charsets.UTF_8)
        if (tempFile.exists()) {
            if (settingsFile.exists()) {
                settingsFile.delete()
            }
            tempFile.renameTo(settingsFile)
        }
    }

    private fun loadFromDisk(): UserSettings {
        if (!settingsFile.exists() || settingsFile.length() == 0L) {
            return UserSettings()
        }
        return try {
            val text = settingsFile.readText(Charsets.UTF_8)
            deserializeSettings(text)
        } catch (_: Exception) {
            UserSettings()
        }
    }

    private fun serializeSettings(settings: UserSettings): String {
        val sb = StringBuilder()
        sb.append("version=${settings.schemaVersion}\n")
        sb.append("discreetMode=${settings.isDiscreetModeEnabled}\n")
        sb.append("screamSensitivity=${settings.screamSensitivity}\n")
        sb.append("motionSensitivity=${settings.motionSensitivity}\n")
        sb.append("countdownDuration=${settings.countdownDurationSeconds}\n")
        sb.append("autoStart=${settings.autoStartProtectionOnBoot}\n")
        sb.append("contacts_count=${settings.contacts.size}\n")
        for (c in settings.contacts) {
            sb.append("contact=${c.id}|${escapeField(c.name)}|${escapeField(c.phoneNumber)}|${escapeField(c.telegramChatId ?: "")}|${c.isPrimary}|${escapeField(c.relationship)}\n")
        }
        return sb.toString()
    }

    private fun deserializeSettings(text: String): UserSettings {
        var version = 1
        var discreet = false
        var sensitivity = 0.80f
        var motionSens = 1.0f
        var countdown = 10
        var autoStart = false
        val contacts = mutableListOf<EmergencyContact>()

        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val parts = line.split("=", limit = 2)
            if (parts.size != 2) continue
            val key = parts[0]
            val value = parts[1]

            when (key) {
                "version" -> version = value.toIntOrNull() ?: 1
                "discreetMode" -> discreet = value.toBoolean()
                "screamSensitivity" -> sensitivity = value.toFloatOrNull() ?: 0.80f
                "motionSensitivity" -> motionSens = value.toFloatOrNull() ?: 1.0f
                "countdownDuration" -> countdown = value.toIntOrNull() ?: 10
                "autoStart" -> autoStart = value.toBoolean()
                "contact" -> {
                    val tokens = value.split("|")
                    if (tokens.size >= 5) {
                        val id = tokens[0]
                        val name = unescapeField(tokens[1])
                        val phone = unescapeField(tokens[2])
                        val chatId = unescapeField(tokens[3]).ifBlank { null }
                        val isPrimary = tokens[4].toBoolean()
                        val relationship = if (tokens.size >= 6) unescapeField(tokens[5]) else "Emergency Contact"
                        contacts.add(
                            EmergencyContact(
                                id = id,
                                name = name,
                                phoneNumber = phone,
                                telegramChatId = chatId,
                                isPrimary = isPrimary,
                                relationship = relationship
                            )
                        )
                    }
                }
            }
        }

        return UserSettings(
            contacts = contacts,
            isDiscreetModeEnabled = discreet,
            screamSensitivity = sensitivity,
            motionSensitivity = motionSens,
            countdownDurationSeconds = countdown,
            autoStartProtectionOnBoot = autoStart,
            schemaVersion = version
        )
    }

    private fun escapeField(field: String): String {
        return field.replace("|", "\\|").replace("\n", "\\n")
    }

    private fun unescapeField(field: String): String {
        return field.replace("\\|", "|").replace("\\n", "\n")
    }
}
