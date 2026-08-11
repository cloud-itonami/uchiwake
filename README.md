# uchiwake 内訳

**World product bill-of-materials / GTIN knowledge graph** — the product-level layer beneath
kabuto 兜. Tier-B supply-chain actor, R0 design-only. ADR-2606081800.

The executable supply-chain classification actor is owned by `cloud-itonami/uchiwake`. The
reusable GTIN substrate remains separate in `cloud-itonami/gtin`, and registry orchestration lives
in `cloud-itonami/action-gtin-registry`. Existing `did:web:*.etzhayyim.com`, AT Protocol, and
Radicle identities remain compatibility identifiers; this repository move preserves actor
continuity.

kabuto wires **company → company** supply edges. uchiwake goes one level **down** — to the
**trade item itself**, keyed on the GS1 **GTIN**, decomposed into its bill of materials
(**product → part → raw material**), plus the **process** steps that make it, the **logistics**
legs that move it, and the **design/standard** refs that specify it — and one level **up**, via
**ownership** edges that roll a brand-owning **subsidiary** to its **ultimate parent** (子会社,
GLEIF Level-2 relationship records).

The lens is the kabuto lens: **supply-chain resilience + corporate-power transparency**. Where a
product's material inputs, processing, or transport concentrate onto a single source or a single
jurisdiction, that concentration is surfaced — routed to **redundancy + accountability**, never a
target-list and never a clone/counterfeit recipe (G2).

## Worldwide-coverage design

| Dimension | Public source (R1 ingest target, G7-gated) |
|---|---|
| Product identity (GTIN) | GS1 GDSN + GS1 Verified; open mirrors Open Food/Beauty/Products Facts |
| Classification | GS1 GPC brick + UNSPSC (existing 18,342 codes) + HS code (WCO) |
| Brand-owner → company | GS1 prefix licensee → GLEIF LEI (kabuto `org.corp.*`) |
| Subsidiary → parent (子会社) | GLEIF Level-2 Relationship Records (RR) |
| BOM / materials | public teardowns, ingredient labels, supplier-list filings, EU DPP |
| Process / logistics | public origin declarations, UN Comtrade HS flows, factory-list pledges |
| Design / standards | cited IEC/ISO/JEDEC/USB-IF specs, public regulatory monographs |

R0 ships a **bounded real seed**; full-universe ingest (hundreds of millions of GTINs) is **R1**
and Council + operator gated (G7).

## Run

```bash
bb -m uchiwake.methods.ingest            # offline bridge + seed → products.merged (live = G7-gated)
bb -m uchiwake.methods.analyze            # resilience report + derived concentration datoms
bb -m uchiwake.methods.crosscheck         # measured uchiwake ⇄ kabuto coverage linkage
bb -m uchiwake.methods.adapters.openfoodfacts   # bulk-ingest normalizer: OFF records → datoms
python3 -m unittest discover -s tests -p 'test_*.py' -v   # 27 tests
```

### Bulk-ingest adapters (the worldwide-coverage path)

`src/uchiwake/src/uchiwake/methods/adapters/openfoodfacts.cljc` is the first concrete bulk normalizer: it turns
[Open Food Facts](https://world.openfoodfacts.org) records (a CC-BY-SA open dataset of ~3M+ real
food/beverage trade items, each with a real GTIN + brand + ingredient list) into uchiwake
`:product` / `:material` / `:bom.edge` datoms — GTIN-validated (mod-10), ingredient % → bounded
`%mass` edges, every datom `:representative` (OFF is crowd-sourced). `ingest.py` auto-routes any
`wire/data/openfoodfacts*.json` file through it and splices the result into `products.merged`.
The LIVE fetch of the full OFF dump (and GS1 GDSN / GLEIF-RR) stays **G7 / operator-gated**; the
adapter runs on a local file so the scale-path is proven offline first.

## Honesty (R0)

11 products (3 real GTINs; 2 `:authoritative`), 18 parts, 26 materials, 46 BOM edges, 10 process
steps (incl. `:design`), 5 logistics legs, 7 design refs, 3 ownership edges. `:representative`
decompositions, GS1 check-digit-validated GTINs, bounded criticality estimates — never an
authoritative recipe or a contract figure. Company refs wire to REAL kabuto companies;
`crosscheck.py` measures linkage (80.8%) + reverse coverage (6.4% of kabuto's supply-chain
companies have product detail) and reports the not-yet-ingested gap with a prioritized worklist.
See `CLAUDE.md` for the full gate list.
