[![Build Status](https://travis-ci.org/englishtown/stash-hook-mirror.png)](https://travis-ci.org/englishtown/stash-hook-mirror) [![Coverage Status](https://coveralls.io/repos/englishtown/stash-hook-mirror/badge.png)](https://coveralls.io/r/englishtown/stash-hook-mirror)

#Stash Repository Hook for Mirroring

The following is a plugin for Atlassian Stash to provide repository mirroring to a remote repository.


* `atlas-run`   -- installs this plugin into the product and starts it on localhost
* `atlas-debug` -- same as `atlas-run`, but allows a debugger to attach at port 5005
* `atlas-debug --jvm-debug-suspend` -- same as `atlas-debug` but waits for the debugger to attach
* `atlas-cli`   -- after atlas-run or atlas-debug, opens a Maven command line window:
                 - 'pi' reinstalls the plugin into the running product instance
* `atlas-help`  -- prints description for all commands in the SDK

Full documentation is always available at:

https://developer.atlassian.com/display/DOCS/Introduction+to+the+Atlassian+Plugin+SDK
