from hail.utils.java import handle_py4j, Env, joption
from hail.typecheck import enumeration

class FunctionDocumentation(object):
    @handle_py4j
    def types_rst(self, file_name):
        Env.hail().utils.FunctionDocumentation.makeTypesDocs(file_name)

    @handle_py4j
    def functions_rst(self, file_name):
        Env.hail().utils.FunctionDocumentation.makeFunctionsDocs(file_name)


def wrap_to_list(s):
    if isinstance(s, list):
        return s
    else:
        return [s]

def get_env_or_default(maybe, envvar, default):
    import os

    return maybe or os.environ.get(envvar) or default

def get_URI(path):
    return Env.jutils().getURI(path)

@handle_py4j
def new_temp_file(n_char = 10, prefix=None, suffix=None):
    return Env.hc()._jhc.getTemporaryFile(n_char, joption(prefix), joption(suffix))

storage_level = enumeration('NONE', 'DISK_ONLY', 'DISK_ONLY_2', 'MEMORY_ONLY',
                            'MEMORY_ONLY_2', 'MEMORY_ONLY_SER', 'MEMORY_ONLY_SER_2',
                            'MEMORY_AND_DISK', 'MEMORY_AND_DISK_2', 'MEMORY_AND_DISK_SER',
                            'MEMORY_AND_DISK_SER_2', 'OFF_HEAP')