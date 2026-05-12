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
- ⚠️ **User information panel** (name, photo, ratings, vehicles, comments)
  - A profile screen exists, but currently it mainly shows account identity and logout.
  - Full profile fields (photo, vehicle list, comments, rating histogram) are not yet fully surfaced.
- ⚠️ **Map with rides and passenger locations**
  - Ride discovery/search UI exists, but no actual map screen with drag/search/center behavior is wired in current UI.
- ❌ **Favorite starting locations with different marker on map**
  - Not found.
- ⚠️ **Add new ride or ride request with required attributes**
  - Create/request flows exist and data models include many required fields.
  - Need verification that all required attributes are exposed in UI forms (e.g., cancellation grace window and full timing tolerances).
- ⚠️ **Upload photos (users/cars/locations)**
  - Image loading and placeholders exist, but no complete end-to-end media upload flow identified across all required entities.
- ⚠️ **Ratings 1–5 overwrite previous + histogram in user panel**
  - Rating submission flow exists (review submission), but overwrite semantics and histogram UI are not clearly implemented.

## 2.1 Back-end

- ✅ **Shared backend for synchronization**
  - Firebase-based remote data source is present and integrated via repositories.
- ✅ **Reasonable backend simplicity for course scope**
  - Architecture supports in-memory/offline-style app behavior with local Room cache and sync logic.

## 2.2 Integration with External Services (pick one)

- ✅ **Weather integration option selected**
  - IPMA API integration exists and weather conditions can be evaluated for ride cancellation logic.
- ⚠️ **Weather cancellation UX/automation**
  - Core evaluation logic exists; verify that user-facing warning/cancellation workflow is fully connected in all ride flows.
- ⚠️ **Payment alternative balance tracking**
  - User balance update support exists in repository layer; complete payment workflow/UI not evident.

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
  - Room cache used for ride requests with local observation.
- ✅ **Wi-Fi preloading**
  - Explicit preload-on-Wi-Fi path exists.
- ⚠️ **Cache policy rationale + advanced eviction criteria + auditable logs**
  - Basic stale eviction exists; advanced criteria and detailed auditable eviction logging are limited.

### 3.2 Anti-Adversarial/Fraud Mechanisms

- ❌ **Explicit anti-fraud/meta-moderation mechanisms**
  - No robust outlier/meta-moderation implementation identified in code.
- ⚠️ **Data minimization/privacy considerations**
  - Some scoping exists naturally by feature design, but no explicit privacy threat model/mechanisms found.

### 3.3 Disconnected Operation

- ✅ **Allow operations while offline**
  - Pending operation queue exists for request creation (and sync replay).
- ⚠️ **Conflict resolution + user notifications after reconnection**
  - Sync replay exists; full conflict resolution strategy and user-facing reconciliation notifications appear limited.
- ⚠️ **Offline new rides + new ride requests**
  - Request pathway is present; equivalent ride-offer offline queueing needs verification.

---

## Recommended short-term action list before checkpoint

1. Implement a real **map screen** with markers for rides, passengers, and favorites.
2. Complete **profile panel richness** (photo, vehicles, comments, ratings histogram).
3. Finish **pagination/lazy loading** TODO for ride search list.
4. Enforce **rating overwrite semantics** at backend/query level and add histogram rendering.
5. Strengthen **offline conflict handling + reconnection notifications**.
6. If selecting feature 3.2, implement at least one concrete anti-fraud mechanism and document threat coverage.
7. Expand **cache eviction policy** and add auditable logging tied to ranking criteria.

