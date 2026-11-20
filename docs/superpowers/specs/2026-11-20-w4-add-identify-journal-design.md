# W4: Add plant, species search, identify, journal

Wave W4 of the Android UI/UX programme. Built on W3 (branch head a9d35cb). Goal: every W4 item below scores 9.5 or higher for both council members on a live lab build, judged by the "Scoring bar" in council-w3-brief.md.

Source: /home/t3/fytorium-ux/w4-design-draft.md. Spyros accepted every recommendation on 2026-11-19 (D1-D17, Q1-Q8). Each decision below names the draft item it implements. Nothing here reopens them.

Reused by name, not redesigned:
- the C1/C1b status line under the top bar title, with Try again in its explanation, and the full-screen "Update Fytorium" stop (W1);
- drafts that survive rotation, theme change and process death (W3 round 8), and the DiscardDialog ("Discard" / "Keep editing");
- DialogTitle and DialogBody with the fit rules C37, C39 and C41 (title at most two lines, body scrolls inside, buttons always visible, edge dividers only over real content);
- receipts drawn over the navigation bar (W2 A13a, A13h) and W1's Undo rules;
- nothing moves under the finger (W2 A1, A1c);
- the error line under Save (C23);
- photo sources (Take photo / Choose photos) and the owner-accepted gallery-grant limit (W3, after C35). I found no item called "F9" in the W1-W3 specs; I read the request's "limit F9" as this gallery-grant limit;
- place keeping by item key (C27, C34, C35, C40);
- "Create a site" from a form: ask first (C26), open the site form, return with the new site selected (C15, C20).

UI wording below is in straight quotes. The app keeps showing typographic quotes where it does now, as in the "Matches" line.

## 1. Scope

### Council items in W4

| Area | Sol | Opus |
|---|---|---|
| Species search | S18 search results, S19 species confirmation | S11 species search |
| Add plant | S20 add plant details, F2 first plant with a site, F3 first plant without a site | S12 add/edit form (add part), F4 add plant |
| Identify | S39 upload, S40 results, S41 confirmation, F18 identify by photo | S19 identify from photos, F5 identify by photo |
| Journal and photos | S33 household journal, S34 plant photo updates, S35 add photo update, S37 original photo, S38 delete photo dialog, F19 add journal photo | S20 photo updates, S21 Journal tab, F8 photo update / journal |
| Offline, journal part | F30 offline use (journal) | F14 offline (journal) |

Findings behind these rows, all in scope: audit F6 (add flow), audit F12 and F13 photo bullets, Sol U6, U7, U11, U26, U32, U33, U34, U40 (photo delete only); Opus U9, U20, U21.

Carried notes, all in scope:
- K1. "rubber plant" misses Ficus elastica and offers Spider Plant and Snake plant; "peace lily" finds nothing.
- K2. "sansevieria" shows two identical "Sansevieria" cards.
- K3. Result cards show "No reference photo".
- K4. Species search keyboard: Enter adds a space instead of searching; the keyboard covers "Search species" and pushes the whole screen, top bar included.
- K5. A search with no match shows only "Fytorium refused the last request" in the status line, and the old results vanish.
- K6. A quick add saves drainage = no and growth stage = seedling.
- K7. Photo updates in landscape at 1.3x: the list is a 67 dp strip under the Refresh and Add buttons.
- K8. The plant page's "Add photo" tile opens the gallery, not the form.
- Identify reachable and realistic in the lab (Opus F5 and the lab's fake "Testoria picta").

Regression only, must not get worse: Sol S36 taken-date picker; Sol F20 browse photos (closed in W3; W4 adds zoom and offline through S37 and S33); Sol F13 / Opus S16 change species, which uses the same search (C16, C27, C31); Sol U21 offline Add, re-checked once; W1 names on Today; W2 rows not moving; every W3 C-item on the pages W4 touches.

### Out of scope
- W5: the create-site form itself (length, "Draft" wording; Sol S50, F21, Opus F9) and everything under More. W4 only links to it.
- W6: the Journal tab's place in the bottom bar, Today "+" switching the bar to Plants, Back targets across tabs, unsaved edits lost on a tab switch.
- The web UI. Server changes keep the web building and its tests passing, nothing more.
- Data quality of names that sources send.

## 2. Decisions

### Species search (draft D1-D4, K1-K5)

- W4-P1. Search page. Top bar: Back, title "Add plant" (or "Change species" when opened from a plant), C1 status line. Body, in order: the "Species name" field with supporting text "Common or scientific name"; a text button with a camera icon, "Identify from photos" (W3's leave question stays when a query is typed); the results; a footer "Names from GBIF (CC BY 4.0) and Wikidata (CC0)." The old "Species search" heading and the separate "Search species" button go. The field gets a trailing search icon button, 48 dp, content description "Search species". On Add the field is focused and the keyboard open when the page opens; on Change species it is not.
- W4-P2. Keyboard (K4). The keyboard's action key is Search. Enter or Search runs the search, never inserts text, and closes the keyboard. App-wide, the window resizes for the keyboard instead of panning: the top bar and status line never move, and the content area above the keyboard shrinks. This applies to every screen with a text field, so W3 forms are regression-checked (T26).
- W4-P3. Running a search. Progress overlays the top edge (W3 rule). The previous results stay on screen until the new answer replaces them. A new answer scrolls the list to the top. A failed search leaves the previous results and says why in the status line.
- W4-P4. Result card (S18, K3). The whole card is one tap target, at least 72 dp tall, with no "Choose" button. Left: an 80 dp square reference photo, cropped, 12 dp corners. Right: the headline (common name in the member's language, else the scientific name), the scientific name without author in a muted colour, "Matches "<name>"" when the matched name is neither of those, "Close match" for fuzzy matches (no percentage), and one small line "Photo: <creator>, <licence>" when a photo exists. While the photo loads, the square is a plain tinted surface; when no photo exists it shows a leaf icon on the tint, never the words "No reference photo". Content description: "<headline>, <scientific name>. Choose this species."
- W4-P5. Ties (D2 O2). When two cards share a headline and the second line, both show the full scientific name with author as the second line. The server filter (SV1) should make this rare.
- W4-P6. No match (K5). The server keeps answering 422. The app treats a 422 for a query it sent (1-200 characters) as "no match" and shows, in the content, under the field: "No species called "<query>"", then "Check the spelling, try the scientific name, or identify it from photos.", then an "Identify from photos" button. The status line stays empty for a no-match. Connection loss, an unreachable server and any other refusal still go to the status line, and the previous results stay.
- W4-P7. Choosing (S19). A card that needs no confirmation opens the add form at once. A card that needs confirmation opens a dialog: title "Is this your plant?"; body: the reference photo across the dialog width at 4:3, the headline, the scientific name, "Close match" if fuzzy, and the photo credit as a link to its source; buttons "Back" and "Yes, this one". "Yes, this one" opens the add form (or, on Change species, W3's review). The dialog follows C37, C39 and C41 in landscape at 1.0x and 1.3x.
- W4-P8. Coverage outcomes (K1, K2). The server changes in SV1 give these first cards in a fresh household: "rubber plant" → Ficus elastica first; "peace lily" → a Spathiphyllum in the first three; "fredslilje" → Spathiphyllum wallisii in the first three; "monstera" → Monstera deliciosa in the first three; "sansevieria" → Dracaena trifasciata in the first three, with no "Sansevieria" placeholder card; "jade plant" and "Crassula ovata" → Crassula ovata first. "rubber plant" does not list Spider Plant or Snake plant.
- W4-P9. A species choice never expires (D4, Sol U7). The app sends `confirmed: true` for every species the user picked, by tapping an exact card or by "Yes, this one". The server accepts that choice after its receipt is gone (SV2). If the server still refuses the species, the form shows "This species can't be used any more. Choose it again." under Save, and the species card's "Change" is the way out.

### Add plant (draft D5-D8, D17; Sol S20, F2, F3; Opus S12, F4; K6)

- W4-P10. Form order: species card, Name, Site, Last watered, "Keep this photo" (only after Identify, W4-P23), "Save plant", the error line (C23), then "More details", folded. Top bar: Back, "Add plant", status line.
- W4-P11. Species card. A 72 dp reference photo, the headline, the scientific name without author, and a "Change" text button. Change goes back to the results the species came from (search or identify), with the query, results and photos kept, and keeps every form value. Picking another species replaces only the species.
- W4-P12. Name. Empty by default. The name the plant will get shows as a muted placeholder, with "Leave empty to use the species name" under it, by the C13 mechanism (the value stays empty; TalkBack reads "Empty, shows <name>").
- W4-P13. Site (Sol F3, U6). No sites: in place of the dropdown, the sentence "Plants live in a site, such as Kitchen or Balcony." and a "Create a site" button. It asks first, as C26 does: title "Create a site?", body "<name> is not added yet. Your choices stay here when you come back.", buttons "Keep editing" and "Create a site". The existing site form opens; after saving, the add form returns with the new site selected (C20). Back from the site form returns to the add form with its values. One site: preselected. Two or more: "Choose a site", no preselection.
- W4-P14. Last watered (audit F6). A "Last watered" label and one row of chips that wraps: "Don't know" (selected by default), "Today", "Yesterday", "A few days ago", "About a week ago", "Pick a date". "A few days ago" means 3 days before the household's today, "About a week ago" 7 days. "Pick a date" opens the Material date picker limited to the last 365 days, future days disabled; after a pick the chip reads the date ("12 Nov"). The app sends `last_watered_on` (SV3), or nothing for "Don't know".
  - "Don't know": Water is due on the day the plant is added, as today (owner answer Q3).
  - A date: History shows "Watered · <member>" on that day, undoable there like any completion; Water's next date counts from it. If that date is already past, the plant shows late, which is true. "Today" records the watering now, so Today lists the Water row as done for this plant.
- W4-P15. Attributes start as "Not set" (D6, K6, Opus U9). Pot type, soil type, plant size, growth stage and fertilizer type get a first option "Not set", selected by default. Drainage becomes a choice "Not set" / "Yes" / "No", default "Not set". Grow light, near AC and near heater stay switches, off by default (off adds no adjustment). The schedule treats "Not set" as no adjustment. "About this plant" lists only set values (W3 P8). The feed's Missing information card names the missing pot type, as it already does for pot size (owner answer Q2). Edit plant shows the same "Not set" options; existing plants keep their stored values.
- W4-P16. Save. "Save plant" never looks disabled for a missing field: a tap without a site puts "Choose a site in your household." under Site (the line W3 already reserves) and saves nothing. Offline, during an Update stop, or while plant changes are blocked, it looks disabled and explains on tap with W3's sentences ("Adding a plant needs a connection." and the others). Field errors appear under their fields, the rest under Save (C23).
- W4-P17. After Save (D17). The new plant page opens with the receipt "Added <name> to <site>", no Undo; Delete stays in the plant menu. <name> is the display name (nickname, else common name, else scientific name).
- W4-P18. Drafts. These survive rotation, theme change and process death: the query, the shown results, the chosen species (key, names, confirmed), Name, Site, Last watered (including a picked date), "Keep this photo" and its photo, More details values and whether it is open. Back from the form with any value entered asks with the DiscardDialog: title "Discard this plant?", body "<species headline> is not added yet." Back from a form with nothing entered returns to the results without asking. The search page keeps W3's rule: Back with a typed query asks first.

### Identify (draft D9-D11; Sol S39-S41, F18, U11; Opus S19, F5, U20)

- W4-P19. Entry points: "Identify from photos" on the search page (Add and Change species) and in the no-match message (W4-P6).
- W4-P20. Input state. Top bar: Back, "Identify from photos", status line. Body:
  1. "Add one to five photos of the same plant. Leaves and flowers work best."
  2. A photo strip: 96 dp square thumbnails of the chosen photos in order, each with a 48 dp × button on its corner. Content descriptions: "Photo 2 of 3" and "Remove photo 2 of 3". While fewer than 5 photos are chosen, two 96 dp tiles follow: "Take photo" and "Choose photos". The picker offers at most the number of photos still allowed.
  3. One line: "Photos go to Pl@ntNet to identify the plant, without location data." and a "Details" text button. Details opens a dialog, title "About identify photos", body "Up to 5 photos, 15 MB each and 40 MB in total. Fytorium shrinks them and removes location and camera data before sending. Fytorium keeps a photo only if you tick Keep this photo when you add the plant.", button "Close".
  4. "Identify", a filled button. Without photos it looks disabled and says "Add a photo first." on tap.
  5. When Pl@ntNet's quota is 50 or less: "<N> identifications left today."
- W4-P21. Results state. After a successful identify, the inputs give way to: the same photos as a compact 56 dp strip without × buttons, followed by a "Change photos" text button; the heading "Best matches"; the result cards, best score first. "Change photos" returns to the input state with the photos; the results stay until the photos change.
- W4-P22. Identify result card (S40). Same layout and tap rule as W4-P4, with a 96 dp photo: headline (Fytorium's common name, else Pl@ntNet's first common name), Pl@ntNet's scientific name without author, "97% match", and "Now called <accepted name>" only when GBIF's accepted name differs. No GBIF line, no author, no rank.
- W4-P23. Confirm (S41, Sol F18). Tapping a card opens "Is this your plant?": the user's first photo and the reference photo side by side at equal size, captioned "Your photo" and "Reference photo"; then headline, scientific name, "97% match", the credit link; buttons "Back" and "Yes, this one". "Yes, this one" is the confirmation the server requires and opens the add form, or W3's review on Change species. No separate confirm page.
- W4-P24. Keep this photo (D11, owner answer Q4). On the add form, after Identify only: a row with a 56 dp thumbnail of photo 1, a checkbox "Keep this photo", ticked by default, and "It becomes the plant's first photo." When the user picks a candidate, the app copies photo 1 into app-private draft storage, so this photo survives process death whatever its source. On Save, the app creates the plant, then uploads that photo as a photo update taken today, with no note and no health. The server makes it the cover (SV6). The plant page opens with "Added <name> to <site>". If only the photo upload fails, the receipt reads "Added <name> to <site>. Its photo didn't upload." The app deletes its copy after the upload, after a discard, or when the form is left without saving.
- W4-P25. Outcomes in the content, not the status line:
  - No species (422): "Pl@ntNet didn't recognise this plant.", "Try a clear photo of a leaf or flower, or search by name.", buttons "Change photos" and "Search by name".
  - Quota used up (429): "No identifications left today.", "Search by name instead.", button "Search by name". "Identify" then looks disabled and says "No identifications left today." on tap.
  - Pl@ntNet unavailable (503) goes to the status line: "Pl@ntNet isn't responding", with the server's sentence and Try again, which reruns the identify with the same photos. Offline and an unreachable Fytorium use the normal C1 lines; "Identify" explains "Identifying needs a connection."
- W4-P26. Identify drafts. Photos, results and an open dialog survive rotation and theme change. After process death the photos and results return; a gallery photo with only a temporary grant cannot be sent again (accepted W3 limit). Back from the add form returns to the results with the photos. Back from Identify with photos chosen asks with the DiscardDialog: title "Discard these photos?", body "Your <N> photos are not used yet."

### Journal and photo updates (draft D12-D16; Sol S33-S38, F19, U32-U34, U40; Opus S20, S21, F8, U20, U21; K7, K8)

- W4-P27. Journal page. Top bar: title "Journal", a "+" icon (content description "Add a photo update"), status line. No Refresh button, no explanatory sentence; pull to refresh. Cards newest taken date first, as now. Empty: "No photos yet" and an "Add your first photo" button, which does what "+" does.
- W4-P28. Plant photos page, from the plant page's "See all" and photo tiles. Top bar: Back, title "Photos of <name>", a "+" icon ("Add a photo of <name>"), status line. Same cards without the plant name.
- W4-P29. Card (Sol S33, S34; Opus S20, S21). The photo fills the card width at 4:3, centre-cropped, loaded from the new display size (SV7); its space is fixed before it loads. Under it: on the Journal "<plant name> · 15 Nov 2026", where the plant name is its own 48 dp link that opens the plant page; on a plant's page just the date. Then "Cover photo" when it is the cover, "Health: <value>" when set, and the note in full. One ⋮ icon button at the end of the first text line, content description "Actions for the 15 Nov 2026 photo of <name>", with "Use as cover" (absent on the cover) and "Delete". Tapping the photo opens the viewer. Cards sit in a grid with one column per 360 dp of width, so a phone in portrait shows one column and landscape shows two (K7).
- W4-P30. Viewer (Sol S37, U33). Full screen on a dark background. A top bar over the photo shows Back and "<name> · 15 Nov 2026". The photo fits the screen. Pinch zooms from 1x to 5x. Double-tap toggles 1x and 2.5x around the tapped point. When zoomed, one finger pans, stopping at the edges. The display size shows at once, and the original replaces it when loaded, without moving the photo. System Back and the arrow both return to the list at the same place (C10, C27). Offline it shows the cached display size, and the status line says "Offline · showing a smaller copy".
- W4-P31. Add a photo update (Sol S35, F19, U11; Opus U20, U21; K8). Entry points: Journal "+" (through the plant picker, W4-P32), the plant photos page "+", and the plant page's "Add photo" tile, which now opens this form directly (K8). Top bar: Back, "Add a photo of <name>", status line. Body, in order:
  1. With no photo: two tiles, "Take photo" and "Choose photo". With a photo: the photo full width at 4:3, decoded small, with "Replace" (a menu: Take photo, Choose photo) and "Remove".
  2. "Taken on" with W3's date picker (S36 unchanged: default today, future days disabled).
  3. "Photo health", default "Not set".
  4. "Note".
  5. "Add photo update". Without a photo it looks disabled and says "Choose a photo first." on tap.
  Upload progress overlays the top edge. After success, the page the user started from shows again (Journal, plant photos page or plant page), the new card in its place, with the receipt "Added a photo of <name>", no Undo. On failure every value stays and the reason shows under the button (C23). Leaving with a photo or text asks with the DiscardDialog, as now.
- W4-P32. Plant picker (D12). Journal "+" opens "Choose a plant": Back, the W3 Plants grid grouped by site with its search field, without "+" and Graveyard. Tapping a plant opens W4-P31 for it. Back from that form returns to the picker, Back again to the Journal. The picker keeps its query and place through rotation and process death.
- W4-P33. Delete (Sol S38, U40; owner answer Q5). Dialog title "Delete this photo?", body "Delete the 15 Nov 2026 photo of <name>? This can't be undone.", buttons "Cancel" and "Delete". After success the receipt reads "Deleted the 15 Nov 2026 photo of <name>", no Undo. Only the user's own delete removes a card at once; other cards may move up after it, since the user confirmed a dialog and was not touching the list.
- W4-P34. Use as cover. The receipt reads "<name>'s cover photo changed", no Undo. The labels change in place.
- W4-P35. Offline and status (D16, Sol U34, F30; Opus F14). The Journal page, plant photos page, add form, viewer, picker, Identify and the add flow use the C1/C1b status line. PhotoNotice's offline sentence, "Refresh photos", "Refresh quota" and in-content progress bars are removed. The app keeps the Journal list and each plant's photo list in CareCache, per member, wiped at logout, and keeps display images in the existing thumbnail disk cache. Offline, the last lists and their photos show, with "Offline · updated hh:mm"; "+", ⋮ actions and "Add photo update" look disabled and explain on tap ("Photo changes need a connection."). When the connection returns, the line clears and the lists reload without a tap. Changes from another device apply at the next pull, return to the page or app resume (W2 A1c); the user's own upload, delete and cover change apply at once.
- W4-P36. Place keeping. The Journal, plant photos page and picker return to the card the user was on after the viewer, a form, a plant page, rotation, theme change and process death, keyed by photo or plant id (C35 mechanism).

### Across W4

- W4-P37. Every new dialog (species confirm, identify confirm, About identify photos, Create a site?, Discard this plant?, Discard these photos?, Delete this photo?) uses DialogTitle and DialogBody and meets C37, C39 and C41 in landscape (800 × 360 dp) at 1.0x and 1.3x.
- W4-P38. Accessibility. Every tap target is at least 48 dp. Result cards, photo thumbnails, × buttons, ⋮ buttons, chips and the viewer have the content descriptions given above. Chips announce their selected state. Contrast is at least 4.5:1 for text and 3:1 for icons and dividers.

## 3. Server and API changes

Each change lists the test that proves it. The test stack stays WireMock-based (tests/stack/mappings). OpenAPI (api/openapi.yaml) is updated for every field, and openapi.test.ts passes.

- SV1. `GET /api/species/search` (D1, D2). Response shape unchanged; the web keeps working.
  1. Local stage, always live. Name matching by words: every query word must start a word of the name. Trigram similarity is used only when the word stage, exact matches and the remote stage find nothing.
  2. Remote stage, only when no local candidate is an exact unique match (today's condition for calling GBIF). It runs in parallel: GBIF `species/match` as now, and Wikidata `action=query&list=search&srsearch=<query> haswbstatement:P846&srnamespace=0&srlimit=10`, then one `wbgetentities` call for those items with `props=claims|labels|aliases&languages=en|nb|el`. Items without P846 (GBIF taxon ID) are dropped. The first 5 GBIF keys are checked with GBIF `species/{key}`: kingdom Plantae, rank species or below; a synonym is replaced by its accepted key and names. The whole remote stage has an 8 s deadline, with 3 s per request.
  3. Placeholder filter: drop candidates whose canonical name is one word at rank SPECIES, whose scientific name contains " spec" or " sp." or "hybr", or whose GBIF status is DOUBTFUL.
  4. Order: exact name matches, then local word matches, then Wikidata order, then GBIF match and its alternatives, then fuzzy. Duplicates by accepted key keep their first place. At most 10 candidates.
  5. Names for remote candidates: when the species is not stored, `common_name` is W1's `pickCommonName` over that item's Wikidata labels and aliases in the member's language. `matched_name` is the label or alias that matched the query. A remote candidate needs no confirmation when the query equals one of its labels or aliases, ignoring case, accents and punctuation, and no other candidate has that name. Otherwise it needs confirmation.
  6. Cache: a new table `species_search_cache (query text PRIMARY KEY, candidates jsonb NOT NULL, expires_at timestamptz NOT NULL)`, where query is the trimmed, lowercased, whitespace-collapsed query. It stores the remote stage result: taxonomy plus each item's labels and aliases in en, nb and el. Common names and the local stage are computed per request. Lifetime 7 days for a non-empty result, 1 day for an empty one. A result where Wikidata or GBIF failed is not cached. Writes delete expired rows. The test clock drives expiry.
  7. Limits and failures: every Wikidata and GBIF request sends `User-Agent: Fytorium (+<configured origin>)`. At most 20 Wikidata requests per minute per server process. After a Wikidata 429 the server skips Wikidata for its Retry-After, default 60 s. A skipped or failed Wikidata call leaves GBIF and local results, returned without notice. When every source fails and nothing local matches: 503 "Species search is unavailable. Try again in a minute." No match at all stays 422.
  Tests: API tests with WireMock mappings for "rubber plant" (Wikidata returns a synonym key, accepted key first; Spider Plant and Snake plant stored locally are not returned), "sansevieria" (GBIF alternatives with the two placeholder records are dropped), an exact alias (no confirmation), a cache hit (a second search sends no Wikidata request, checked with WireMock's request journal; 6 days later still none; 8 days later one), Wikidata 429 (GBIF results returned, not cached, no Wikidata call for 60 s), every source down (503), and no match (422). Unit tests for the word rule, the placeholder filter and the ordering. Migration up and down.
- SV2. Species choice without a receipt (D4). `chosenSpecies` keeps today's rule while the receipt lives. Without a live receipt, it accepts `gbif_key` when `confirmed === true` and the key is already in `species` or GBIF `species/{key}` returns an accepted Plantae species at species rank or below; taxonomy comes from the stored row or GBIF, never from the client. Otherwise 422 with today's messages. Tests: Save 10 minutes after the search (test clock) → 201; Save after the receipt map is cleared (a server restart) → 201; an unknown key, a non-Plantae key or a genus → 422; `confirmed: false` without a receipt → 422. Applies to `POST /api/plants` and `PUT /api/plants/{id}/species`.
- SV3. `POST /api/plants` accepts optional `last_watered_on` (D8). It must be a valid YYYY-MM-DD date, not after the household's today, not more than 365 days before it, and not before `birthday` when one is sent. Otherwise 422 `{ errors: { last_watered_on: "Choose a date in the last year, not in the future." } }` or "Choose a date on or after the birthday.". In the same transaction as the plant, the server records one completed Water event: `occurred_at` is now for today and 00:00 household time for an earlier date, as the Planta import does; `due_on` is Water's due date just before it; `by_member` and `by_name` are the member's; a new `request_id`; no `completion_notice_sends` rows. Tests: water due at last watered + interval; the event is listed in history on that day; Undo from history works and Water is then due on the day of adding; no completion notice is sent; an invalid date gives the 422 and no plant.
- SV4. Nullable attributes (D6). Migration: drop NOT NULL and DEFAULT on `pot_type`, `soil_type`, `plant_size`, `growth_stage`, `fertilizer_type` and `drainage`. Down: fill nulls with the old defaults, then restore both. `readPlant` and `potErrors` accept null for these; the TypeScript types allow null. Ripple, each with a test: the engine gives no adjustment for null (existing behaviour, assert it); the no-drainage warning needs `drainage = false`; Missing information lists a null pot type; Repot of a plant with a null pot and its Undo restore null; weather hardiness treats a null pot type as potted; the Planta import stores null for attributes Planta does not send; a fertilize completion on a plant with null fertilizer type records no linked Water (the interval still uses the "liquid or unknown" rule). `pnpm typecheck`, unit, API and browser tests pass, so the web still builds.
- SV5. Identification candidates (D10). Each candidate gets `common_name` from `withCommonNames`, else Pl@ntNet's first common name; `identification_canonical_name`, Pl@ntNet's name without author; and the accepted key and names when GBIF `species/{key}` says Pl@ntNet's key is a synonym. Candidates without a GBIF key are mapped by `species/match` as now. The request to Pl@ntNet adds `lang=<member language>`. Tests: the fake returns a synonym key and gets the accepted key and names back, with `identification_canonical_name` kept; `common_name` falls back to Pl@ntNet's; the Pl@ntNet request carries `lang`.
- SV6. Covers (D11). In `addPhoto`'s transaction: when the plant has no cover photo, the new photo becomes the cover. In `deletePhoto`'s transaction: when the deleted photo was the cover, the newest remaining photo (taken date, then upload time) becomes the cover. Tests: first upload is the cover; a second is not; deleting the cover promotes the newest; deleting the last leaves no cover.
- SV7. Display size (D13). `GET /api/photos/{id}/display` returns the original resized to at most 1080 px on the long edge, JPEG quality 80, made on request from the stored original, with `Cache-Control: private, max-age=604800`. Nothing new is stored. Thumbnails stay 400 px. Tests: 200 with an image at most 1080 px on its long edge; 404 for another household's photo; OpenAPI covers it.
- Every other decision is app-only. W4-P8 needs SV1, W4-P9 SV2, W4-P14 SV3, W4-P15 SV4, W4-P22 SV5, W4-P24 SV6 and W4-P29 SV7. The journal list and photo upload APIs stay as they are.

## 4. Lab setup the implementer provides

Owner-approved (Q8). Synthetic data only. Never touch namespace `fytorium`. Document each step in /home/t3/fytorium-ux/lab-runbook.md.

- L1. Lab Pl@ntNet fake with real species. A separate WireMock container, `plantnet-lab`, on the lab compose network, with mappings kept outside the repo (for example /work/lab-fakes/plantnet). `lab-run` deletes untracked files in /work/fytorium on sync, so the mappings must not live there. compose.lab.yaml sets the app's `PLANTNET_BASE_URL` to it. tests/stack/mappings stays unchanged for the test suite. The default answer to `POST /identify/all`, for any photos, lists these real GBIF keys (checked with read-only GBIF requests on 2026-11-19; each has CC0 or CC BY occurrence images):
  - 0.91 Monstera deliciosa, gbif 2868241, common names ["Swiss cheese plant"];
  - 0.05 Epipremnum aureum, gbif 2868323;
  - 0.02 Sansevieria trifasciata, gbif 2770610, a GBIF synonym of Dracaena trifasciata 11041822, to show "Now called";
  - 0.01 Ficus elastica, with no gbif id, so the server maps it by name to 7835333.
  `remainingIdentificationRequests` is 42. A WireMock scenario switches the answer: state "no-match" (`results: []`), "quota" (HTTP 429), "down" (HTTP 503), back to "Started". Switch with `curl -X PUT http://plantnet-lab:8080/__admin/scenarios/plantnet/state -d '{"state":"no-match"}'` from the `emu` container, and set it back afterwards.
- L2. Synthetic photos on the emulator: six generated JPEGs, 1600 × 1200 except one 1200 × 1600 portrait, each a plain colour with a large number "1" to "6". No real photos. Pushed to /sdcard/Pictures/FytoriumTest and media-scanned so the photo picker lists them. The emulator's virtual camera serves "Take photo".
- L3. A second household with no sites. The server allows one household per database (`households_singleton` index), so this needs a second database: `fytorium_empty` on the same postgres, and a second app container, `fytorium-app-empty`, from the same image with the same settings except `DATABASE_URL`, joined to the compose network under the alias `app` and normally stopped. A script, /home/t3/fytorium-ux/lab-household `empty|main`, stops one app container, starts the other, waits for health, sets the test clock to 2026-11-15T08:00Z, and force-stops the Android app. The app's old tokens fail on the other database, so it shows login; log in as `synthetic-ux-owner`. On the empty database that creates a new household with no sites and no plants. `lab-household main` switches back, then log in as `synthetic-ux-owner` again. Never run both app containers at once. The implementer runs the switch both ways once and records the result in the runbook.
- L4. Reference photos (K3). Before the council round, every first card in T1 shows a reference photo in the lab. If one does not, the implementer finds the cause (3 s timeout, a cached failure, or the app's request order) and records it. The draft did not verify it.

## 5. Accepted limits and shortcuts

- A1. A search where Wikidata or GBIF failed returns what the other sources found, without saying so, and is not cached.
- A2. A species chosen from Identify whose receipt has expired loses the "keep Pl@ntNet's taxonomy" flag; its taxonomy then comes from GBIF.
- A3. A result card's common name may differ from the name the plant shows once its names load (W1's naming rule).
- A4. The W3 gallery-grant limit applies to identify photos and the add-photo form after process death. The kept identify photo is copied at once, so it is not affected.
- A5. The lab Pl@ntNet fake returns the same list for any photo.
- A6. The viewer shows one photo; no swipe between photos.
- A7. "Not set" fertilizer uses the "liquid or unknown" interval, but a fertilize completion does not also record Water.
- A8. Existing plants keep their stored attribute values; nothing guesses which were silent defaults.
- A9. Photo delete has no Undo (Q5); upload and cover change give receipts without Undo.
- A10. The reference photo is GBIF's first CC0 or CC BY occurrence image, so it can be a poor picture.
- A11. The Wikidata request limit is counted per server process (`shortcut:` comment, as in app-rate-limit.ts).
- A12. Offline, the Journal has cached lists and display images. Originals, uploads, deletes and cover changes need a connection.
- A13. Search runs on Search or Enter, not while typing.
- A14. adb cannot pinch. The council checks double-tap zoom and pan live; pinch is covered by an instrumented or Robolectric test.
- A15. If the identify photo upload fails after the plant is saved, the photo is not retried; the receipt says so.

## 6. Acceptance checks

Run on the lab emulator as in the W3 brief (`emu ui`, `emu text`, `emu key 66`, `emu shot`, test clock 2026-11-15, read-only SQL, `svc wifi/data`, font scale 1.3, rotation, dark mode). Each check passes only if every listed result holds. A check that fails on a normal path is S2 or worse, which keeps the item at 9 or below (Scoring bar). Leave the lab as the W3 brief says, and remove plants, photos and sites you created.

Search
- T1 (coverage). In a fresh search, with `emu ui` reading names: "rubber plant" → first card Ficus elastica, no Spider Plant or Snake plant card; "peace lily" → a Spathiphyllum in the first three; "fredslilje" → Spathiphyllum wallisii in the first three; "monstera" → Monstera deliciosa in the first three; "sansevieria" → Dracaena trifasciata in the first three; "jade plant" and "Crassula ovata" → Crassula ovata first. Also search "gummifiken" and record what comes first; it is not a pass condition (section 8). Every first card shows a reference photo; no card anywhere says "No reference photo".
- T2 (duplicates). "sansevieria" and "monstera": no two cards share headline and second line; no name contains "spec", "sp." or "hybr".
- T3 (keyboard). Open Add: the field is focused with the keyboard open. Top bar title bounds are equal before and after the keyboard opens. `emu text rubber` then `emu key 66` runs the search, adds no character, and closes the keyboard. Repeat on Edit plant's Name and Species names: the top bar does not move.
- T4 (no match). Search "zzqxw": the content shows "No species called "zzqxw"", the sentence and "Identify from photos"; the status line is empty. Search "monstera" then "zzqxw" with Wi-Fi and data off: the monstera results stay and the line says offline.
- T5 (choose). Tap an exact card: the form opens with no dialog. Tap a "Close match" card: "Is this your plant?" shows the large photo, names and credit link; "Back" returns to the same results at the same place; "Yes, this one" opens the form. The dialog in landscape at 1.0x and 1.3x meets C37, C39 and C41.
- T6 (expiry). Choose a species, set the test clock 10 minutes later, Save: the plant is saved. Repeat with `docker restart fytorium-app-1` between choosing and Save, then set the clock again.

Add plant
- T7 (form). After choosing: species card with photo, common and scientific name, "Change"; Name empty with the species name as placeholder (TalkBack text "Empty, shows …"); Site; Last watered with "Don't know" selected; "Save plant"; "More details" folded. With one site in the household it is preselected. Tap count from Plants "+" to a saved plant with one site and "Don't know": 4 taps plus typing.
- T8 (Change). Type a name, pick "Yesterday", tap "Change", choose another species: the name and Yesterday are still set.
- T9 (defaults). Add a plant without opening More details. SQL: pot_type, soil_type, plant_size, growth_stage, fertilizer_type and drainage are NULL. About this plant lists none of them. The feed has no "no drainage" warning for it; its Missing information card names the pot type.
- T10 (last watered). With the test clock on 2026-11-15, add with "A few days ago": History shows "Watered · Synthetic Owner" under 12 Nov; Water's due date is 12 Nov plus the shown interval (late if that is past); Today has no Water row for it unless due. Undo it from History: Water becomes due on 15 Nov. Add another with "Don't know": Water is due today. "Pick a date": days after 15 Nov and before 15 Nov 2025 cannot be picked.
- T11 (save). Tap "Save plant" with no site chosen (two or more sites): "Choose a site in your household." appears under Site and Save stays in place. With a site: the plant page opens with "Added <name> to <site>". Offline: Save looks disabled and explains on tap.
- T12 (drafts). Choose a species, type a name, pick a site and "A few days ago", open More details and set pot type: rotate, switch dark mode, then press Home and run `emu sh am kill com.epaflix.fytorium` and reopen: species card, name, site, chip, More details state and pot type are back; Save works. Back with values asks "Discard this plant?".
- T13 (no site, L3). Switch to the empty household. Add → choose a species → the form shows "Plants live in a site, …" and "Create a site". Tap it: "Create a site?" asks first; continue, save a site, and the form is back with the site selected and the species and name kept. Save works. Switch back to main.

Identify
- T14 (input). From Add, "Identify from photos": the intro line, "Take photo" and "Choose photos" tiles, the one privacy line and "Details". Choose photos 1 and 2: thumbnails show; `emu ui` gives "Photo 1 of 2" and "Remove photo 2 of 2"; × removes photo 2 and the tiles stay in place. Choosing a sixth photo is not possible. "Details" opens the dialog; it fits in landscape at 1.3x.
- T15 (results). With photos 1 and 2, "Identify": the strip shrinks, "Best matches" lists cards with reference photo, common name, scientific name without author and "% match"; the Sansevieria card says "Now called Dracaena trifasciata"; no GBIF line, no author.
- T16 (confirm). Tap Monstera deliciosa: the dialog shows "Your photo" and "Reference photo" side by side, names, "91% match", "Back" and "Yes, this one". Confirm: the form shows the species card and "Keep this photo", ticked. Back returns to the results with the photos.
- T17 (keep photo). Save: the plant page's cover is photo 1, the Journal's first card is that photo, and Photos of <name> has one card. Repeat with the box unticked: no photo. Tap count from Plants "+" to the saved plant via Identify with one site: 7 taps plus the system picker.
- T18 (outcomes). Set L1 to "no-match": the content message with "Change photos" and "Search by name"; the status line is empty. "quota": "No identifications left today."; Identify explains on tap. "down": the status line reads "Pl@ntNet isn't responding" and its Try again reruns. Set L1 back to "Started". Rotation and process death on the results keep photos and results.

Journal and photos
- T19 (Journal). Journal tab: title "Journal", "+", no Refresh button, no sentence above the cards. Cards: large photo, "<plant> · <date>", health and note, one ⋮. ⋮ shows "Use as cover" and "Delete" (no "Use as cover" on a cover). The plant name opens the plant page; Back returns to the same card.
- T20 (plant photos). Plant page → See all: "Photos of <name>", "+", the same cards without plant names. The plant page's "Add photo" tile opens the add form (K8).
- T21 (add). Journal "+" → "Choose a plant" (grid with search) → a plant → the form → "Choose photo" → the photo shows full width with Replace and Remove → "Add photo update": back on the Journal with "Added a photo of <name>"; the new card is first, and screenshots before and after show no other card moving while the receipt shows. The Taken on picker still blocks future dates (S36). Tap count from the Journal tab: 4 taps plus the system picker.
- T22 (viewer). Tap a photo: full screen with "<name> · <date>"; double-tap zooms around the tapped point; a swipe pans when zoomed; Back (arrow and system) returns to the same card. Rotate in the viewer: still open at the same photo.
- T23 (delete). ⋮ → Delete: "Delete this photo?", body names date and plant, buttons "Cancel" and "Delete"; after Delete, "Deleted the 15 Nov 2026 photo of <name>". Delete a cover: the newest remaining photo becomes the cover.
- T24 (offline). Open the Journal and a plant's photos online. Turn Wi-Fi and data off, kill and reopen the app, open the Journal: cards and photos show, the line reads "Offline · updated hh:mm", "+" and ⋮ actions explain on tap. Turn them on: the line clears without a tap.
- T25 (U21). Offline, Plants "+" and Today "+" look disabled and explain on tap.

Regression and fit
- T26. Change species: search, confirm dialog and review show common names first; receipt "Changed <plant> to <species>"; Back keeps the plant page place (C16, C27, C31). Edit plant, Species names and This plant's schedule keep their top bars still with the keyboard open. Today rows do not move on a burst of taps; W1 names on Today unchanged.
- T27 (K7). Plant photos and Journal in landscape at 1.3x: no buttons or notices above the list; two columns of cards; the list fills the height between the top bar and the navigation bar.
- T28. Every W4 dialog in landscape at 1.0x and 1.3x: title at most two lines, body scrolls inside with dividers only over hidden content, buttons visible.
- T29. TalkBack text via `emu ui` for: result cards (headline, scientific name, choose), identify cards (with score), thumbnails and ×, ⋮ ("Actions for the 15 Nov 2026 photo of Monty"), chips (with selected state), the viewer photo.

### Item to check map

| Item | Checks |
|---|---|
| Sol S18, Opus S11 | T1-T5 |
| Sol S19 | T5 |
| Sol S20, Opus S12 (add) | T7-T12 |
| Sol F2, Opus F4 | T6, T7, T10-T12 |
| Sol F3 | T13 |
| Sol S39, Opus S19 | T14, T18 |
| Sol S40 | T15 |
| Sol S41 | T16 |
| Sol F18, Opus F5 | T14-T18 |
| Sol S33, Opus S21 | T19, T23, T24 |
| Sol S34, Opus S20 | T19, T20, T27 |
| Sol S35 | T21 |
| Sol S36 (regression) | T21 |
| Sol S37 | T22 |
| Sol S38 | T23 |
| Sol F19, Opus F8 | T20, T21 |
| Sol F20 (regression), Sol F30, Opus F14 (journal) | T22, T24 |
| Sol U21 (re-check) | T25 |
| Sol F13, Opus S16 (regression) | T26 |
| K1 | T1 |
| K2 | T2 |
| K3 | T1 |
| K4 | T3 |
| K5 | T4 |
| K6 | T9 |
| K7 | T27 |
| K8 | T20 |
| All | T28, T29 |

## 7. Implementation order

Each slice builds, passes its tests and can be reviewed alone. Server slices run the full web suite (`./scripts/stack test`) and typecheck; Android slices run unit, Robolectric screenshot and accessibility tests, with screenshots updated, not deleted.

1. Server search (SV1). Cache migration, Wikidata stage, filter, ordering, limits, User-Agent, OpenAPI. Includes L4's diagnosis of the lab placeholder.
2. Search screen (W4-P1 to W4-P8, the app-wide keyboard resize). Depends on 1 for live coverage only; the screen works against today's server.
3. Server add (SV2, SV3, SV4). Migration and ripple tests.
4. Add form (W4-P9 to W4-P18): species card, "Not set" choices, chips, site creation, receipt, drafts. Needs 3.
5. Lab L1, L2, L3 and the runbook. Can run beside 1-4.
6. Identify (SV5, W4-P19 to W4-P26 except Keep this photo). Needs 5 for live checks.
7. Covers and Keep this photo (SV6, W4-P24). Needs 4 and 6.
8. Photo pages status and cache (W4-P35, the C1 line on photo pages, pull to refresh, CareCache lists).
9. Journal cards, viewer, add form, picker, delete (SV7, W4-P27 to W4-P34, W4-P36). Needs 8.
10. Fit and accessibility pass over every W4 page (W4-P37, W4-P38), then a full run of T1-T29 by the implementer before the council round.

## 8. Not verified

- Wikidata's ranking beyond the five queries in the draft, including "gummifiken" and "jade plant"; Wikidata's real request limit for an identified User-Agent.
- Pl@ntNet's `lang` parameter and its behaviour for nb and el.
- WireMock 3.13.2 scenario switching through `PUT /__admin/scenarios/{name}/state` (L1).
- The two-container household switch (L3), including the Android app returning to login when its tokens belong to the other database.
- Why the lab showed "No reference photo" (L4).
- That Undo from History accepts a backdated Water event without a `care_actions` row, as the Planta import's events have none.
- The emulator camera for "Take photo".
- The GBIF keys in L1 were checked with read-only requests on 2026-11-19; GBIF can change them.
