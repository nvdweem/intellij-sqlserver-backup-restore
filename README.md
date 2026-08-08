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

# Tests

```
./gradlew test
```

Two kinds live in the same task:

- **Unit tests** run everywhere and need nothing. They cover the SQL as text (escaping, the batch splitting, the
  download's chunk arithmetic) and what `plugin.xml` declares against the classes it names.
- **Platform tests** (`PluginRegistrationTest`) boot a real headless IDE, so they check the things that are only wired up
  at runtime: the notification group, the virtual file system behind the remote picker, and the two settings services.
- **Integration tests** (`*IT`) talk to a real SQL Server and run the statements the plugin actually sends - backup,
  restore, differential chains, session killing, directory listings, and a download reassembled byte for byte.

The integration tests **skip themselves when no server answers**, so a checkout without one still goes green. The reason
they skipped is in `build/reports/tests/test/index.html`, or run with `--info`. They default to a local default instance;
point them somewhere else with either system properties or environment variables:

```
./gradlew test -Dit.sqlserver.url="jdbc:sqlserver://localhost:1433;encrypt=true;trustServerCertificate=true;" \
               -Dit.sqlserver.user=sa -Dit.sqlserver.password="mypass1!"

IT_SQLSERVER_URL=... IT_SQLSERVER_USER=... IT_SQLSERVER_PASSWORD=... ./gradlew test
```

They need a login that can `CREATE DATABASE`, `BACKUP`, `RESTORE`, `KILL` other sessions and browse the server's
filesystem - in practice `sysadmin`. Everything they create is named `ij_it_*` and dropped afterwards, so anything left
behind under that prefix is from a run that crashed. Backups are written to the server's own default backup directory
and deleted afterwards, which only works when the server shares a filesystem with the tests; against a remote server the
`.bak` files stay behind.

CI runs the whole task, so there the integration tests skip. Pointing them at a service container would run them for
real.

# Docker mssql for Linux testing

The whole suite passes against SQL Server on Linux as well as on Windows, which is worth re-checking whenever the
filesystem or path handling changes — the separators differ, and the queries behind the file picker take different
branches:

```
docker run -d --name mssql-it -e ACCEPT_EULA=Y -e MSSQL_SA_PASSWORD='Str0ng!Passw0rd' -e MSSQL_PID=Developer \
  -p 14330:1433 mcr.microsoft.com/mssql/server:2022-latest

./gradlew test -Dit.sqlserver.url="jdbc:sqlserver://localhost:14330;encrypt=true;trustServerCertificate=true;" \
               -Dit.sqlserver.user=sa -Dit.sqlserver.password='Str0ng!Passw0rd'

docker rm -f mssql-it
```

Two things that were expected to break there and don't:

- `xp_instance_regread`, which the default-backup-directory query uses, works on Linux — SQL Server emulates the
  registry key and returns `/var/opt/mssql/data`.
- `sys.dm_os_enumerate_fixed_drives` reports `/` as the single root, so the picker has somewhere to start.

The older image still works too:

```
/mnt/user/temp
docker run -it --rm -e "ACCEPT_EULA=Y" -e "SA_PASSWORD=mypass1!" -p 1433:1433 -v /mnt/user/temp:/opt/backups mcr.microsoft.com/mssql/server:2019-latest
```