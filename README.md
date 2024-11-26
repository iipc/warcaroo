Warcaroo
=======

Warcaroo is browser-based web crawler designed to archive web content into 
[WARC](https://en.wikipedia.org/wiki/WARC_(file_format)) (Web ARChive) files for preservation.

Running browsers remotely over SSH
----------------------------------

As browsing is CPU intensive it can be useful to the run the browser on a cluster of servers. Warcaroo can
launch a browsers on remote servers via SSH using the `--ssh` option. The remote server does not need 
Warcaroo or Java installed, just the browser.

**Example:** Run 3 browsers: server1, server2 and local

    java -jar warcaroo.jar --ssh server1.example.org --ssh server2.example.org

When using the `--ssh` option, the `--browser`, `--browser-options`, `--ssh-command`, `--workers`  options
apply to the `--ssh` option that follows them. To set `--browser` or `--workers` for the local browser put 
them at the end of the command-line.

**Example:** Run 4 workers on server1, 3 workers on server2 and no browser locally:

    java -jar warcaroo.jar --workers 4 --ssh server1.example.org --workers 3 --ssh server2.example.org --workers 0

**Example:** Use `chromium` on server1 and `google-chrome-stable` locally:

    java -jar warcaroo.jar --browser chromium --ssh server1.example.org --browser google-chrome-stable

You can set custom SSH options using `--ssh-command`:

**Example:** Use an SSH key file and port 2222 when connecting to server1

    java -jar warcaroo.jar --ssh-command 'ssh -i id_rsa -p 2222' --ssh server1.example.org

You can even use SSH's SOCKS feature to proxy web requests through via the machine warcaroo is running on.
This can be useful if you want all the requests to come from a single IP address or if the remote servers do not have
direct internet access.

**Example:*** Tunnel web traffic through an SSH SOCKS proxy on port 1080

    java -jar warcaroo.jar --browser-options '--proxy-server=socks://127.0.0.1:1080' --ssh-command 'ssh -R 1080' --ssh server1.example.org
