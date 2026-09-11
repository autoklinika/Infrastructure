# Synthetic Stage 1 export

All coordinates (near 0° latitude/longitude), SSIDs, locally administered BSSIDs,
device fields and notes are invented. Nothing represents the workshop or a real device.

These files were produced by the Android exporter in `RepositoryExportTest` using
an in-memory Room database under Robolectric. They include BSSID transition,
disconnect/reconnect, weak accuracy, a sampling gap and an unlocated sample.
Reserved neighbor/anchor CSVs contain headers only. Do not put real measurements here.

The Python tests package these fixed bytes deterministically and validate the ZIP.
Android tests independently exercise serialization and export, including two identical
exports from the same database. Recreating random record IDs is not a byte-identical
fixture refresh; do not replace committed fixtures with real phone exports.
