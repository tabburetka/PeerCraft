# Fabric 1.21.1 native handoff fixture

Adapted from the 1.16.5 native fixture. Compilation and all native scenarios remain pending. Run only after the current target processes have exited, with the runner RAM guard.

Fixture bundle compiled successfully against the native 1.21.1 APIs (5 tasks, 3 executed). World creation uses WorldOpenFlows with the registered flat preset; native advancements use AdvancementHolder and NBT items use registry-aware decoding. This is compilation evidence only.

Native A → B → A passed in `run-20261009-205455`. Both clients emitted DONE and exited 0; account UUIDs, inventory/ender chest/XP, owner stats/advancement/pet, account bindings and final save assertions passed. Return selected update-with-backup. Evidence: `passed-cycle-20261009/`. Missing optional flite narrator produced startup errors; no progress assertion failed. Other scenario groups remain pending.

Repeated A → B → A → B → A passed in `run-20261009-205708`, with stable world ID and native persisted hosting epochs 1, 2, 3, 4. Progress assertions passed at both returns; both clients exited 0. Evidence: `passed-repeat-20261009/`. Process restart and duplicate-authority negative cases remain pending.
