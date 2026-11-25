package com.guardiangrid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.guardiangrid.GuardianGridApp
import com.guardiangrid.core.models.EmergencyContact
import com.guardiangrid.core.state.EmergencyStateHolder
import com.guardiangrid.modules.communication.persistence.PhoneNumberValidator
import com.guardiangrid.ui.theme.BrandMagenta
import com.guardiangrid.ui.theme.BrandPurple
import com.guardiangrid.ui.theme.CyberTeal
import com.guardiangrid.ui.theme.DarkBackground
import com.guardiangrid.ui.theme.DarkBorder
import com.guardiangrid.ui.theme.DarkSurface
import com.guardiangrid.ui.theme.DarkSurfaceElevated
import com.guardiangrid.ui.theme.DarkSurfaceHighlight
import com.guardiangrid.ui.theme.EmergencyRed
import com.guardiangrid.ui.theme.SafeGreen
import com.guardiangrid.ui.theme.TextDisabled
import com.guardiangrid.ui.theme.TextPrimary
import com.guardiangrid.ui.theme.TextSecondary
import com.guardiangrid.ui.theme.TextTertiary
import com.guardiangrid.ui.theme.WarningAmber
import java.util.UUID

/**
 * Dedicated Emergency Contacts Management Screen.
 *
 * Implements:
 * - Primary vs Secondary emergency responder designation.
 * - Real-time international phone number validation (ITU-T E.164).
 * - Safe persistence and state synchronization.
 * - Add, edit, delete, and promote-to-primary workflows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmergencyContactsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val container = (context.applicationContext as? GuardianGridApp)?.container
    val state by EmergencyStateHolder.state.collectAsState()
    val repo = container?.settingsRepository

    var showContactDialog by remember { mutableStateOf(false) }
    var contactToEdit by remember { mutableStateOf<EmergencyContact?>(null) }
    var contactToDelete by remember { mutableStateOf<EmergencyContact?>(null) }

    val contacts = state.trustedContacts
    val primaryContact = contacts.firstOrNull { it.isPrimary } ?: contacts.firstOrNull()
    val secondaryContacts = contacts.filterNot { it.id == primaryContact?.id }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
    ) {
        // --- Top Bar ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onNavigateBack,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = TextPrimary
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "EMERGENCY CONTACTS",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black,
                    color = TextPrimary,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Primary & Secondary Emergency Responders",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
        }

        // --- Summary & Add Button ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "REGISTERED RESPONDERS",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            color = TextTertiary,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = if (primaryContact != null) "Primary: ${primaryContact.name} (+${secondaryContacts.size} secondary)" else "No contacts configured",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (primaryContact != null) CyberTeal else WarningAmber
                        )
                    }

                    Button(
                        onClick = {
                            contactToEdit = null
                            showContactDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BrandPurple),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "Add Contact", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // --- Contact List ---
        if (contacts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(54.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No Emergency Contacts",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Add at least one trusted primary contact who will receive distress coordinates, audio evidence notices, and emergency alerts.",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(contacts, key = { it.id }) { contact ->
                    ContactCard(
                        contact = contact,
                        isPrimary = contact.isPrimary,
                        onSetPrimary = {
                            repo?.setPrimaryContact(contact.id)
                            repo?.syncToStateHolder()
                        },
                        onEdit = {
                            contactToEdit = contact
                            showContactDialog = true
                        },
                        onDelete = {
                            contactToDelete = contact
                        }
                    )
                }
            }
        }
    }

    // --- Add/Edit Contact Dialog ---
    if (showContactDialog) {
        ContactEditDialog(
            contactToEdit = contactToEdit,
            onDismiss = { showContactDialog = false },
            onSave = { name, phone, chatId, isPrimary, relationship ->
                val newContact = EmergencyContact(
                    id = contactToEdit?.id ?: UUID.randomUUID().toString(),
                    name = name.trim(),
                    phoneNumber = phone.trim(),
                    telegramChatId = chatId.trim().ifBlank { null },
                    isPrimary = isPrimary,
                    relationship = relationship
                )

                if (contactToEdit == null) {
                    repo?.addContact(newContact)
                } else {
                    repo?.updateContact(newContact)
                }
                repo?.syncToStateHolder()
                showContactDialog = false
            }
        )
    }

    // --- Delete Confirmation Dialog ---
    if (contactToDelete != null) {
        val target = contactToDelete!!
        AlertDialog(
            onDismissRequest = { contactToDelete = null },
            containerColor = DarkSurfaceElevated,
            title = {
                Text(text = "Delete Emergency Contact?", color = TextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    text = "Are you sure you want to remove ${target.name} (${target.phoneNumber}) from your emergency responder list?",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        repo?.deleteContact(target.id)
                        repo?.syncToStateHolder()
                        contactToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmergencyRed)
                ) {
                    Text(text = "Delete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { contactToDelete = null }) {
                    Text(text = "Cancel", color = TextSecondary)
                }
            }
        )
    }
}

@Composable
private fun ContactCard(
    contact: EmergencyContact,
    isPrimary: Boolean,
    onSetPrimary: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(14.dp),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(if (isPrimary) BrandMagenta else DarkBorder)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(if (isPrimary) BrandMagenta.copy(alpha = 0.2f) else DarkSurfaceHighlight),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = if (isPrimary) BrandMagenta else TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = contact.name,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isPrimary) BrandMagenta.copy(alpha = 0.2f) else DarkSurfaceHighlight)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (isPrimary) "PRIMARY" else "SECONDARY",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Black,
                                    color = if (isPrimary) BrandMagenta else TextTertiary
                                )
                            }
                        }

                        Text(
                            text = contact.relationship,
                            fontSize = 11.sp,
                            color = CyberTeal
                        )
                    }
                }

                Row {
                    if (!isPrimary) {
                        IconButton(onClick = onSetPrimary, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = Icons.Default.StarBorder,
                                contentDescription = "Make Primary",
                                tint = WarningAmber,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else {
                        Box(modifier = Modifier.padding(4.dp)) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "Primary",
                                tint = BrandMagenta,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit",
                            tint = TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = TextTertiary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Details Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(DarkSurfaceElevated)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Phone, contentDescription = null, tint = SafeGreen, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = contact.phoneNumber, fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                }

                if (!contact.telegramChatId.isNullOrBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = CyberTeal, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "Chat: ${contact.telegramChatId}", fontSize = 11.sp, color = TextSecondary)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactEditDialog(
    contactToEdit: EmergencyContact?,
    onDismiss: () -> Unit,
    onSave: (name: String, phone: String, chatId: String, isPrimary: Boolean, relationship: String) -> Unit
) {
    var name by remember { mutableStateOf(contactToEdit?.name ?: "") }
    var phone by remember { mutableStateOf(contactToEdit?.phoneNumber ?: "") }
    var chatId by remember { mutableStateOf(contactToEdit?.telegramChatId ?: "") }
    var isPrimary by remember { mutableStateOf(contactToEdit?.isPrimary ?: false) }
    var relationship by remember { mutableStateOf(contactToEdit?.relationship ?: "Family") }
    var relationshipExpanded by remember { mutableStateOf(false) }

    val phoneValidation = remember(phone) { PhoneNumberValidator.validateAndNormalize(phone) }
    val isFormValid = name.isNotBlank() && phoneValidation.isValid

    val relationshipOptions = listOf("Family", "Partner", "Parent", "Sibling", "Friend", "Colleague", "Caregiver", "Other")

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurfaceElevated,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(
                text = if (contactToEdit == null) "Add Emergency Contact" else "Edit Emergency Contact",
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Name Field
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Full Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = BrandMagenta,
                        unfocusedBorderColor = DarkBorder
                    )
                )

                // Relationship Selector
                ExposedDropdownMenuBox(
                    expanded = relationshipExpanded,
                    onExpandedChange = { relationshipExpanded = !relationshipExpanded }
                ) {
                    OutlinedTextField(
                        value = relationship,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Relationship") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = relationshipExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = BrandMagenta,
                            unfocusedBorderColor = DarkBorder
                        )
                    )
                    ExposedDropdownMenu(
                        expanded = relationshipExpanded,
                        onDismissRequest = { relationshipExpanded = false },
                        modifier = Modifier.background(DarkSurfaceElevated)
                    ) {
                        relationshipOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option, color = TextPrimary) },
                                onClick = {
                                    relationship = option
                                    relationshipExpanded = false
                                }
                            )
                        }
                    }
                }

                // Phone Field
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Phone Number (E.164)") },
                    placeholder = { Text("+14155552671", color = TextDisabled) },
                    singleLine = true,
                    isError = phone.isNotBlank() && !phoneValidation.isValid,
                    supportingText = {
                        if (phone.isNotBlank() && !phoneValidation.isValid) {
                            Text(phoneValidation.errorMessage ?: "Invalid format", color = EmergencyRed, fontSize = 11.sp)
                        } else if (phone.isNotBlank() && phoneValidation.isValid) {
                            Text("✓ Valid international format (${phoneValidation.normalizedNumber})", color = SafeGreen, fontSize = 11.sp)
                        } else {
                            Text("Include country code (e.g. +1, +44, +91)", color = TextTertiary, fontSize = 11.sp)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = if (phoneValidation.isValid) SafeGreen else BrandMagenta,
                        unfocusedBorderColor = DarkBorder
                    )
                )

                // Telegram Chat ID Field
                OutlinedTextField(
                    value = chatId,
                    onValueChange = { chatId = it },
                    label = { Text("Telegram Chat ID (Optional)") },
                    placeholder = { Text("123456789 or @channel", color = TextDisabled) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = BrandMagenta,
                        unfocusedBorderColor = DarkBorder
                    )
                )

                // Primary Contact Switch
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = "Designate as Primary Responder", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(text = "Alerted first with top delivery priority", fontSize = 10.sp, color = TextSecondary)
                    }
                    Switch(
                        checked = isPrimary,
                        onCheckedChange = { isPrimary = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = BrandMagenta)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name, phoneValidation.normalizedNumber, chatId, isPrimary, relationship) },
                enabled = isFormValid,
                colors = ButtonDefaults.buttonColors(containerColor = BrandPurple, disabledContainerColor = DarkSurfaceHighlight)
            ) {
                Text(text = "Save Contact", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Cancel", color = TextSecondary)
            }
        }
    )
}
