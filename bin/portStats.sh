while true; do 
	d=`date +%H:%M:%S`;
	p8888=`netstat -an|grep 8888|wc -l`;
	p8800=`netstat -an|grep 8800|wc -l`; 
	p3210=`netstat -an|grep 3210|wc -l`; 
	echo $d, 8888 "->" $p8888 "/" 8800 "->" $p8800 "/" 3210 "->" $p3210
	sleep 5; 
done;
