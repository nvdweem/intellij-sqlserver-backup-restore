# intellij-sqlserver-backup-restore
A plugin that allows creating backups and restoring them from the DataGrip context for Microsoft SQLServer databases. <br>
<br>
The plugin built for my own personal use case which means that it will work for databases that are connected through an SSH tunnel.
It supports downloading backups from the remote server to the local machine without using xp_cmdshell command.<br>
<br>
Features:<br>
<ul>
  <li> Creating a backup and storing it on the server </li>
  <li> Creating a backup, storing it and download it right after </li>
  <li> Reading a backup into an existing database </li>
  <li> Reading a backup into a newly created database </li>
</ul><br>
Currently only works with the 2020 EAP versions because the API for the database seems to have been changed.

## Change notes
0.1<br>
    - Initial version. Seems to work fine locally :).
