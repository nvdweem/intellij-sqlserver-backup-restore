# intellij-sqlserver-backup-restore
[Plugin on Jetbrains website](https://plugins.jetbrains.com/plugin/13913)
<!-- Plugin description -->
A plugin that allows creating backups and restoring them from the DataGrip context for Microsoft SQLServer databases.

The plugin built for my own personal use case which means that it will work for databases that are connected through an SSH tunnel.
It supports downloading backups from the remote server to the local machine without using `xp_cmdshell` command.

Features:
- Creating a backup and storing it on the server
- Creating a backup, storing it and download it right after
- Reading a backup into an existing database
- Reading a backup into a newly created database

**Note:** AWS and probably also Azure don't seem to support file based backing up and are not supported by this plugin.
<!-- Plugin description end -->

# Docker mssql for Linux testing
```
/mnt/user/temp
docker run -it --rm -e "ACCEPT_EULA=Y" -e "SA_PASSWORD=mypass1!" -p 1433:1433 -v /mnt/user/temp:/opt/backups mcr.microsoft.com/mssql/server:2019-latest
```