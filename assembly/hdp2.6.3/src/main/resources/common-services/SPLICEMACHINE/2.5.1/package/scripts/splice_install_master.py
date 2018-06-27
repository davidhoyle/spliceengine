import sys, os, fnmatch
from resource_management.libraries.script.script import Script
from resource_management.core.resources import Directory
from resource_management.core.resources.system import Execute, Link
from resource_management.libraries.resources import XmlConfig
from resource_management.libraries.functions import format
from resource_management.libraries.functions.check_process_status import check_process_status
from resource_management.core.exceptions import ComponentIsNotRunning
from resource_management import *
from urlparse import urlparse


reload(sys)
sys.setdefaultencoding('utf8')

class SpliceInstallMaster(Script):
  def install(self, env):
    import params
    self.install_packages(env)
    env.set_params(params)
    self.configure(env)

  def start(self, env, upgrade_type=None):
    import params
    Execute(("touch", params.splice_pid_file))

  def stop(self, env, upgrade_type=None):
    import params
    Execute(("rm", params.splice_pid_file))

  def status(self, env):
    import params
    if not os.path.isfile(params.splice_pid_file):
      raise ComponentIsNotRunning()

  def configure(self, env):
    import params

    hbase_user = params.config['configurations']['hbase-env']['hbase_user']

    params.HdfsResource("/user/splice",
                        type="directory",
                        action="create_on_execute",
                        owner=hbase_user)

    params.HdfsResource("/user/splice/history",
                        type="directory",
                        action="create_on_execute",
                        owner=hbase_user)


    if params.config['configurations'].get('ranger-env') is not None:
      self.install_ranger()


  def install_ranger(self):
    import params
    splice_lib_dir = "/var/lib/splicemachine"
    ranger_home = format('{params.stack_root}/current/ranger-admin')
    ranger_user = params.config['configurations']['ranger-env']['ranger_user']
    ranger_plugins_dir = os.path.join(ranger_home,
                                      "ews/webapp/WEB-INF/classes/ranger-plugins/splicemachine")

    Directory(ranger_plugins_dir,
              owner = ranger_user,
              group = ranger_user,
              create_parents = False
              )

    splice_ranger_jar = self.search_file(splice_lib_dir, "splice_ranger_admin-hdp*.jar")
    db_client_jar = self.search_file(splice_lib_dir, "db-client-*.jar")

    Link(os.path.join(ranger_plugins_dir, splice_ranger_jar),
         to = os.path.join(splice_lib_dir, splice_ranger_jar))
    Link(os.path.join(ranger_plugins_dir, db_client_jar),
         to = os.path.join(splice_lib_dir, splice_ranger_jar))

    hbase_user = params.config['configurations']['hbase-env']['hbase_user']
    hdfs_audit_dir = params.config['configurations']['ranger-splicemachine-audit'][
       'xasecure.audit.destination.hdfs.dir']

    params.HdfsResource(hdfs_audit_dir,
                        type="directory",
                        action="create_on_execute",
                        owner=hbase_user
                        )

  def search_file(self, dir, pattern):
    for file in os.listdir(dir):
      if fnmatch.fnmatch(file, pattern):
        return file
    return None



if __name__ == "__main__":
  SpliceInstallMaster().execute()
