#!/bin/bash -e
#!/bin/bash -vxe

##################################################################
# Location of the Linux kernel.
##################################################################
#srcPath=linux-2.6.33.3
# XXX:$PWD/ makes the path absolute, it is needed for some stupid bug!
srcPath=$PWD/linux-2.6.33.3

##################################################################
# List of files to preprocess
##################################################################
list=""
while read i; do
  list="$list $i"
done < linuxKernelSrcList.txt
##################################################################
# Preprocessing flags
##################################################################
# Hack to change and remove options just for the partial preprocessor.
. jcpp.conf

# Note: this clears $partialPreprocFlags
#partialPreprocFlags="-c linux-redhat.properties -I $(gcc -print-file-name=include) -x CONFIG_ -U __INTEL_COMPILER \
partialPreprocFlags="-c linux-$system.properties -x CONFIG_ -U __INTEL_COMPILER \
  -U __ASSEMBLY__ --include $srcPath/include/generated/autoconf.h"

# XXX: These options workaround bugs triggered by these macros.
partialPreprocFlags="$partialPreprocFlags -U CONFIG_PARAVIRT -U CONFIG_TRACE_BRANCH_PROFILING"
# Encode missing dependencies caught by the typechecker! :-D
partialPreprocFlags="$partialPreprocFlags -U CONFIG_PARAVIRT_SPINLOCKS -U CONFIG_64BIT"

# Flags which I left out from Christian configuration - they are not useful.
# partialPreprocFlags="$partialPreprocFlags -D PAGETABLE_LEVELS=4 -D CONFIG_HZ=100"

gccOpts="$gccOpts -nostdinc -isystem $(gcc -print-file-name=include) -include linux_defs.h -include $srcPath/include/generated/autoconf.h"

flags() {
  base="$1"
  # XXX: again, I need to specify $PWD, for the same bug as above.
  # "-I linux-2.6.33.3/include -I linux-2.6.33.3/arch/x86/include"
  echo "-I $srcPath/include -I $srcPath/arch/x86/include -D __KERNEL__ -DCONFIG_AS_CFI=1 -DCONFIG_AS_CFI_SIGNAL_FRAME=1 -DKBUILD_BASENAME=KBUILD_STR($base) -DKBUILD_MODNAME=KBUILD_STR($base) -DKBUILD_STR(s)=#s"
}

export outCSV=linux.csv
## Reset output
#echo -n > "$outCSV"

##################################################################
# Actually invoke the preprocessor and analyze result.
##################################################################
for i in $list; do
  base=$(basename $i)
  . ./jcpp.sh $srcPath/$i.c $(flags "$base")
  . ./postProcess.sh $srcPath/$i.c $(flags "$base")
#  for j in $listToParse; do
#    if [ "$i" = "$j" ]; then
#      ./parseTypecheck.sh $srcPath/$i.pi
#      break
#    fi
#  done
done
for i in $list; do
  base=$(basename $i)
  ./parseTypecheck.sh $srcPath/$i.pi
done

# The original invocation of the compiler:
# gcc -Wp,-MD,kernel/.fork.o.d
# -nostdinc -isystem /usr/lib/gcc/x86_64-redhat-linux/4.4.4/include
# -I/app/home/pgiarrusso/TypeChef/linux-2.6.33.3/arch/x86/include -Iinclude
# -D__KERNEL__
# -include include/generated/autoconf.h -DCONFIG_AS_CFI=1 -DCONFIG_AS_CFI_SIGNAL_FRAME=1 -D"KBUILD_STR(s)=#s" -D"KBUILD_BASENAME=KBUILD_STR(fork)" -D"KBUILD_MODNAME=KBUILD_STR(fork)"
# -Wall -Wundef -Wstrict-prototypes -Wno-trigraphs -fno-strict-aliasing -fno-common -Werror-implicit-function-declaration -Wno-format-security -fno-delete-null-pointer-checks -O2 -m64 -mtune=generic -mno-red-zone -mcmodel=kernel -funit-at-a-time -maccumulate-outgoing-args -pipe -Wno-sign-compare -fno-asynchronous-unwind-tables -mno-sse -mno-mmx -mno-sse2 -mno-3dnow -Wframe-larger-than=2048 -fno-stack-protector -fomit-frame-pointer -Wdeclaration-after-statement -Wno-pointer-sign -fno-strict-overflow -fno-dwarf2-cfi-asm -fconserve-stack
# -c -o kernel/fork.o kernel/fork.c


# vim: set tw=0:
