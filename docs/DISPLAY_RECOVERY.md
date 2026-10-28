# Shizuku restarts and private displays

Some phones restart Android's ADB daemon when locking or changing USB modes,
even without a physical cable. On the diagnosed MediaTek/Xiaomi device, both
Shizuku and Pocket Agent's shell display service belong to ADB's process group.
When that group is stopped, the private displays lose their owner. Shizuku's
watchdog can restart the backend, but the old displays and their contents are
gone. Pocket Agent cannot keep that process alive through an OS-level stop.

Pocket Agent waits asynchronously for up to a 12-second retry window when
display acquisition encounters a missing/restarting Shizuku binder or pending
display UserService. Each native bind attempt can also consume its existing
bounded startup wait. Other workers and direct phone APIs remain usable while
waiting. A stopped worker releases its reservation; it cannot create a display
later when the backend returns.

Recovery retries acquisition only. A fresh observation after backend loss can
replace the lost lease and returns `display_replaced: true`, telling the model
that the previous private-display state was discarded. Phone actions, uncertain
mutation outcomes, permission denials, failed task cleanup, and expired service
startup attempts are not automatically replayed. If Shizuku remains stopped,
the actual acquisition error is returned after the bounded wait.

This recovery does not suppress Shizuku's crash notification or restore an app's
previous UI. To reduce the underlying USB-mode-triggered restart, the fork's
maintainer recommends **Developer options → Default USB configuration → No data
transfer / Charging only**, with USB debugging left enabled:
https://github.com/thedjchi/Shizuku/wiki/troubleshooting#shizuku-keeps-stopping-randomly

Validation should cover a virtual-display task during an unplugged lock/unlock
cycle. Confirm the new display is usable, the result reports replacement, and an
interrupted action is not replayed. A stable screen-off test with no active
virtual display does not establish this behavior.
