This folder contains java projects to import into Eclipse, to functionally test
that IvyDE works correctly with different kinds of configuration.

Environment:
 * the global configuration of Ivy in the workspace is expected to be the
   default one (using the maven repository).

Expectation:
 * each IvyDE classpath container to resolve correctly
 * the projects are expected to compile correctly

Exception:
 * project 'linked-folder': it is relying on some Eclipse linked folder which
   requires some absolute path. You probably will require to change it to make
   it resolve correctly