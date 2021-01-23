package dev.niels.sqlbackuprestore.ui;

import dev.niels.sqlbackuprestore.query.Client;
import lombok.SneakyThrows;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public interface SQLHelper {
    @SneakyThrows
    static String getDefaultBackupDirectory(Client connection) {
        return (String) connection.getSingle("declare @BackupDirectory nvarchar(512)\n" +
                "if 1=isnull(cast(SERVERPROPERTY('IsLocalDB') as bit), 0)\n" +
                "select @BackupDirectory=cast(SERVERPROPERTY('instancedefaultdatapath') as nvarchar(512))\n" +
                "else\n" +
                "exec master.dbo.xp_instance_regread N'HKEY_LOCAL_MACHINE', N'SOFTWARE\\Microsoft\\MSSQLServer\\MSSQLServer', N'BackupDirectory', @BackupDirectory OUTPUT\n" +
                "\n" +
                "select @BackupDirectory as directory", "directory").get(2, TimeUnit.SECONDS);
    }

    @SneakyThrows
    static List<Map<String, Object>> getDrives(Client connection) {
        return connection.getResult("create table #fixdrv ( Name sysname NOT NULL, Size int NOT NULL, Type sysname NULL )\n" +
                "if exists (select 1 from sys.all_objects where name='dm_os_enumerate_fixed_drives' and type ='V' and is_ms_shipped = 1)\n" +
                "begin\n" +
                "    insert #fixdrv select SUBSTRING(fixed_drive_path, 1, 1), free_space_in_bytes/(1024*1024), drive_type_desc from sys.dm_os_enumerate_fixed_drives      \n" +
                "end\n" +
                "else\n" +
                "begin\n" +
                "    insert #fixdrv (Name, Size) EXECUTE master.dbo.xp_fixeddrives \n" +
                "    update #fixdrv set Type = 'Fixed' where Type IS NULL \n" +
                "end\n" +
                "select * from #fixdrv;\n" +
                "drop table #fixdrv;").get(10, TimeUnit.SECONDS);
    }

    @SneakyThrows
    static List<Map<String, Object>> getSQLPathChildren(Client connection, String path) {
        return connection.getResult("declare @Path nvarchar(255)\n" +
                "declare @Name nvarchar(255)\n" +
                "select @Path = N'" + path + "'\n" +
                "select @Name = null;\n" +
                "\n" +
                "create table #filetmpfin (Name nvarchar(255) NOT NULL, IsFile int NULL, FullName nvarchar(300) not NULL)\n" +
                "declare @FullName nvarchar(300)  \n" +
                "if exists (select 1 from sys.all_objects where name = 'dm_os_enumerate_filesystem' and type = 'IF' and is_ms_shipped = 1)\n" +
                "begin \n" +
                "    if (@Name is null)\n" +
                "    begin \n" +
                "        insert #filetmpfin select file_or_directory_name, 1 - is_directory, full_filesystem_path from sys.dm_os_enumerate_filesystem(@Path, '*') where [level] = 0\n" +
                "    end \n" +
                "    if (NOT @Name is null)\n" +
                "    begin \n" +
                "    if(@Path is null) \n" +
                "        select @FullName = @Name \n" +
                "    else\n" +
                "        select @FullName = @Path \t+ convert(nvarchar(1), serverproperty('PathSeparator')) + @Name \n" +
                "        create table #filetmp3 ( Exist bit NOT NULL, IsDir bit NOT NULL, DirExist bit NULL ) \n" +
                "        insert #filetmp3 select file_exists, file_is_a_directory, parent_directory_exists from sys.dm_os_file_exists(@FullName) \n" +
                "        insert #filetmpfin select @Name, 1-IsDir, @FullName from #filetmp3 where Exist = 1 or IsDir = 1 \n" +
                "        drop table #filetmp3 \n" +
                "    end\n" +
                "end \n" +
                "else      \n" +
                "begin         \n" +
                "    if(@Name is null)\n" +
                "    begin\n" +
                "    if (right(@Path, 1) = '\\')\n" +
                "        select @Path= substring(@Path, 1, len(@Path) - charindex('\\', reverse(@Path)))\n" +
                "    create table #filetmp (Name nvarchar(255) NOT NULL, depth int NOT NULL, IsFile bit NULL )\n" +
                "    insert #filetmp EXECUTE master.dbo.xp_dirtree @Path, 1, 1\n" +
                "    insert #filetmpfin select Name, IsFile, @Path + '\\' + Name from #filetmp f\n" +
                "    drop table #filetmp\n" +
                "    end \n" +
                "    if(NOT @Name is null)\n" +
                "    begin\n" +
                "    if(@Path is null)\n" +
                "        select @FullName = @Name\n" +
                "    else\n" +
                "        select @FullName = @Path +  '\\' + @Name\n" +
                "    if (right(@FullName, 1) = '\\')\n" +
                "        select @Path= substring(@Path, 1, len(@FullName) - charindex('\\', reverse(@FullName)))\n" +
                "    create table #filetmp2 ( Exist bit NOT NULL, IsDir bit NOT NULL, DirExist bit NULL )\n" +
                "    insert #filetmp2 EXECUTE master.dbo.xp_fileexist @FullName\n" +
                "    insert #filetmpfin select @Name, 1-IsDir, @FullName from #filetmp2 where Exist = 1 or IsDir = 1 \n" +
                "    drop table #filetmp2\n" +
                "    end \n" +
                "end \n" +
                "\n" +
                "SELECT Name, IsFile, FullName FROM #filetmpfin ORDER BY IsFile ASC, Name ASC \n" +
                "drop table #filetmpfin").get(10, TimeUnit.SECONDS);
    }
}
