# Communication Module (modules/communication)

**Owner: Member 3**  
**Component:** Resilient Multi-Channel Emergency Dispatch & Offline Persistence  
**Target Branch:** `feature/communication`

## Responsibilities
1. **Telegram Bot API Alert Demonstration:**
   - Formats emergency distress alerts into structured Telegram messages.
   - Transmits GPS coordinates, Google Maps link, distress trigger summary, and audio evidence link.
2. **SMS Fallback Mechanism:**
   - Invokes Android `SmsManager` when internet connectivity is degraded or unavailable.
   - Compact SMS payload format with coordinates and distress code.
3. **Local Emergency Event Persistence & Retry Queue:**
   - Persists outbound alerts locally (SQLite / Room / shared prefs).
   - Exponential backoff retry policies and delivery acknowledgment tracking (`PENDING` $\rightarrow$ `IN_FLIGHT` $\rightarrow$ `DELIVERED`).
4. **Bluetooth Relay (Foundation / Future Extension):**
   - BLE advertisement beacon for nearby GuardianGrid device relay when completely disconnected from cellular/Wi-Fi.

## Interfaces to Implement
- `com.guardiangrid.core.interfaces.EmergencyCommunicator`
- `com.guardiangrid.core.interfaces.DeliveryQueueManager`
- `com.guardiangrid.core.interfaces.BluetoothRelayService`
