#!/bin/bash

java -Xmx48G -Xss1G -jar /home/binpang/guojian/reproduce/TypeChef/TypeChef-0.4.2.jar \
--dumpfuncs --dumpcfg --dumpintracfg --serializeAST --bdd --lexNoStdout --lexdebug --writePI --parserstatistics \
--platfromHeader /home/binpang/guojian/reproduce/Program/proftpd/typechef/platform.h \
--output=debug \
-I /usr/local/include \
-I /usr/include \
-I /usr/lib/gcc/x86_64-linux-gnu/7/include \
-I /usr/lib/gcc/x86_64-linux-gnu/7/include-fixed \
/home/binpang/guojian/temp/test.c

