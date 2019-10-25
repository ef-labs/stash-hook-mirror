[![Build Status](https://travis-ci.org/ef-labs/stash-hook-mirror.png)](https://travis-ci.org/ef-labs/stash-hook-mirror)

# Bitbucket Server Repository Hook for Mirroring

The following is a plugin for Atlassian Bitbucket Server to provide repository mirroring to a remote repository.


* `atlas-run`   -- installs this plugin into the product and starts it on localhost
* `atlas-debug` -- same as `atlas-run`, but allows a debugger to attach at port 5005
* `atlas-debug --jvm-debug-suspend` -- same as `atlas-debug` but waits for the debugger to attach
* `atlas-cli`   -- after atlas-run or atlas-debug, opens a Maven command line window:
                 - 'pi' reinstalls the plugin into the running product instance
* `atlas-help`  -- prints description for all commands in the SDK


Full documentation is always available at:
https://developer.atlassian.com/display/DOCS/Introduction+to+the+Atlassian+Plugin+SDK


Soy documentation is available here:
https://bitbucket.org/atlassian/aui-adg/wiki/versions/product-version-matrix
https://bitbucket.org/atlassian/aui/src/master/src/soy/form.soy


The plugin can be found on the Atlassian Marketplace here:
https://marketplace.atlassian.com/plugins/com.englishtown.stash-hook-mirror

Plugin properties in bitbucket.properties in file, located in the shared folder of server home directory:

`plugin.com.englishtown.stash-hook-mirror.push.attempts=5
plugin.com.englishtown.stash-hook-mirror.push.threads=3
plugin.com.englishtown.stash-hook-mirror.push.timeout=120`

