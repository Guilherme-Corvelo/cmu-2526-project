# SharIST Assignment Requirements Accounting

This checklist maps the project requirements to the current implementation status in this repository.

Legend:
- ✅ Implemented
- ⚠️ Partially implemented / present but incomplete
- ❌ Missing

## 2. System Overview

### Core features

- ✅ **Account creation and authentication**
  - Sign in and register flows exist with role selection (driver/passenger).
- ✅ **User information panel** (name, photo, ratings, vehicles, comments)
  - The profile screen shows identity, balance, role, vehicle details, recent comments, and a 1–5 rating histogram.
  - Users can choose profile and vehicle pictures from the device; photos are loaded through the metered-aware image loader.
- ⚠️ **Map with rides and passenger locations**
  - Ride/request forms include route preview maps, draggable OpenStreetMap support, and favorite-location markers.
  - The dedicated discovery map exists, but full address search/current-location centering should still be demo-tested before submission.
- ✅ **Favorite starting locations with different marker on map**
  - Pickup/destination fields can be saved as favorites and favorites are rendered with a magenta star marker in the request map.
- ✅ **Add new ride or ride request with required attributes**
  - Ride creation captures driver identity, origin/destination, departure time, estimated arrival, capacity, cancellation limit, price, weather rule, and daily/weekly-style recurrence.
  - Passenger cancellations after the ride's penalty-free cancellation limit reduce passenger reliability/reputation.
  - Ride requests capture passenger identity, origin/destination, pickup/arrival walking radius, requested time, before/after tolerance, estimated price, weather rule, and daily/weekly-style recurrence.
- ⚠️ **Upload photos (users/cars/locations)**
  - User and car photo selection is implemented in the profile panel and is propagated into posted rides.
  - Location photo fields exist in models, but the UI for adding location-specific pictures is still not exposed.
- ✅ **Ratings 1–5 overwrite previous + histogram in user panel**
  - Rating/review submission is present and the profile renders a histogram from stored reviews.
  - Re-submission behavior should still be demo-tested against Firestore rules to confirm overwrite semantics in the deployed backend.

## 2.1 Back-end

- ✅ **Shared backend for synchronization**
  - Firebase-based remote data source is present and integrated via repositories.
- ✅ **Reasonable backend simplicity for course scope**
  - Architecture supports Firebase remote state plus local Room cache and pending-operation sync.

## 2.2 Integration with External Services (pick one)

- ✅ **Weather integration option selected**
  - IPMA API integration exists and weather conditions can be evaluated for ride/request cancellation logic.
- ✅ **Weather cancellation UX/automation**
  - Ride and request forms show weather warnings, and periodic cleanup checks weather cancellation conditions.
- ⚠️ **Payment alternative balance tracking**
  - User balance update support exists in repository layer; complete external payment UI is intentionally out of scope because the weather option was selected.

## 2.3 Resource Frugality

- ✅ **Server-side filtering intent and search-oriented retrieval**
  - Search/filter flows are present in repository/view model architecture.
- ⚠️ **Incremental download as UI scroll/zoom requires**
  - RecyclerView near-bottom detection exists, but pagination hook is marked TODO.
- ✅ **Metered data optimization for photos**
  - Placeholder-on-metered and tap-to-load behavior implemented; auto-load on Wi-Fi.

## 3. Advanced Features (pick any 2)

### 3.1 Caching

- ✅ **Cache to minimize repeated downloads / aid outages**
  - Room cache used for rides, requests, bookings, favorites, and pending operations.
- ✅ **Wi-Fi preloading**
  - Explicit preload-on-Wi-Fi path exists.
- ⚠️ **Cache policy rationale + advanced eviction criteria + auditable logs**
  - Basic stale eviction exists; advanced criteria and detailed auditable eviction logging are limited.

### 3.2 Anti-Adversarial/Fraud Mechanisms

- ✅ **Explicit anti-fraud/meta-moderation mechanisms**
  - Meta-moderation worker and trust-score/outlier hooks exist.
- ⚠️ **Data minimization/privacy considerations**
  - Completed ride/request paths anonymize route details in several backend flows; final report should still explain the privacy threat model.

### 3.3 Disconnected Operation

- ✅ **Allow operations while offline**
  - Pending operation queue exists for request and ride creation/cancellation/status updates.
- ⚠️ **Conflict resolution + user notifications after reconnection**
  - Sync replay exists and app notifications summarize sync results; nuanced conflict explanations are limited.
- ✅ **Offline new rides + new ride requests**
  - Both offered rides and ride requests can be queued locally and replayed when connectivity returns.

---

## Remaining polish before final demo

1. Demo-test rating overwrite semantics with two submissions from the same passenger.
2. Add UI for location-specific photos if time allows.
3. Exercise discovery-map current-location/address-search behavior on a real device or emulator.
4. Document cache eviction rationale and fraud/privacy choices in the final report.
